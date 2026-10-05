package com.maghizhan.tabby.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maghizhan.tabby.data.local.TabbyDatabase
import com.maghizhan.tabby.data.local.TransactionRunner
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.ExpenseRepositoring
import com.maghizhan.tabby.data.remote.NotAuthenticatedException
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.sync.ExpenseSyncCoordinator
import com.maghizhan.tabby.data.sync.SyncState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The production sync cycle, exercised against a REAL in-memory Room database
 * and repository fakes.
 *
 * Testing the coordinator against fake DAOs would prove nothing about the thing
 * most likely to be wrong — whether the SQL actually scopes and deletes what the
 * plan says — so the DAO half is real here and only the network half is faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ExpenseSyncCoordinatorTest {

    private lateinit var database: TabbyDatabase
    private lateinit var transactions: TransactionRunner

    private val ownerA = "11111111-1111-1111-1111-111111111111"
    private val ownerB = "22222222-2222-2222-2222-222222222222"
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val t1: Instant = t0.plusSeconds(60)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TabbyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        transactions = object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        }
    }

    @After
    fun tearDown() = database.close()

    /** Repository fake: serves a snapshot, records what was pushed. */
    private class FakeRepository(
        private val snapshot: CompleteSnapshot<RemoteExpenseRow>?,
        private val fetchError: Throwable? = null
    ) : ExpenseRepositoring {
        val upserted = mutableListOf<RemoteExpenseRow>()
        val deleted = mutableListOf<UUID>()

        override suspend fun fetchAll(): CompleteSnapshot<RemoteExpenseRow> {
            fetchError?.let { throw it }
            return snapshot!!
        }

        override suspend fun upsert(rows: List<RemoteExpenseRow>) {
            upserted += rows
        }

        override suspend fun delete(ids: List<UUID>) {
            deleted += ids
        }
    }

    private fun local(
        id: UUID,
        owner: String?,
        state: SyncState,
        remoteId: String? = null,
        amount: String = "10.00",
        updatedAt: Instant = t0
    ) = ExpenseEntity(
        id = id,
        amount = BigDecimal(amount),
        categoryName = "Coffee",
        note = null,
        date = updatedAt,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        syncStateRaw = state.raw,
        remoteId = remoteId,
        ownerId = owner,
        revision = 0
    )

    private fun remote(id: UUID, owner: String, updatedAt: Instant = t0) = RemoteExpenseRow(
        id = id,
        userId = owner,
        amount = BigDecimal("10.00"),
        categoryName = "Coffee",
        note = null,
        date = updatedAt,
        createdAt = updatedAt,
        updatedAt = updatedAt
    )

    private fun coordinator(
        repository: ExpenseRepositoring,
        owner: String? = ownerA
    ) = ExpenseSyncCoordinator(
        dao = database.expenseDao(),
        repository = repository,
        transactions = transactions,
        activeOwnerId = { owner }
    )

    @Test
    fun `a remote row absent locally is inserted as synced`() = runTest {
        val id = UUID.randomUUID()
        val repository = FakeRepository(
            CompleteSnapshot.forTesting(listOf(remote(id, ownerA)), ownerA)
        )

        val outcome = coordinator(repository).synchronize()

        assertEquals(1, outcome.inserted)
        val stored = database.expenseDao().byId(id, ownerA)
        assertEquals(SyncState.SYNCED, stored!!.syncState)
    }

    /**
     * The ordering guarantee. Pushing before reconciling a complete pull
     * re-uploads a row another device deleted, and the next snapshot then
     * confirms it forever — the iOS resurrection bug.
     */
    @Test
    fun `a row deleted remotely is deleted locally and never pushed back`() = runTest {
        val id = UUID.randomUUID()
        database.expenseDao().upsert(
            listOf(local(id, ownerA, SyncState.SYNCED, remoteId = id.toString()))
        )
        val repository = FakeRepository(CompleteSnapshot.forTesting(emptyList(), ownerA))

        val outcome = coordinator(repository).synchronize()

        assertEquals(1, outcome.deletedLocally)
        assertNull(database.expenseDao().byId(id, ownerA))
        assertTrue("a remotely deleted row was pushed back", repository.upserted.isEmpty())
    }

    /** A row that never reached the backend must survive an empty snapshot. */
    @Test
    fun `a never-uploaded local row survives and is pushed`() = runTest {
        val id = UUID.randomUUID()
        database.expenseDao().upsert(listOf(local(id, ownerA, SyncState.LOCAL)))
        val repository = FakeRepository(CompleteSnapshot.forTesting(emptyList(), ownerA))

        val outcome = coordinator(repository).synchronize()

        assertEquals(0, outcome.deletedLocally)
        assertEquals(1, outcome.pushed)
        assertEquals(listOf(id), repository.upserted.map { it.id })
        assertEquals(SyncState.SYNCED, database.expenseDao().byId(id, ownerA)!!.syncState)
    }

    /**
     * A failed pull must not mutate anything locally and must not push. An error
     * is not an empty account, and acting on a partial view is how a transient
     * network failure becomes permanent deletion.
     */
    @Test
    fun `a failed pull mutates nothing locally and pushes nothing`() = runTest {
        val id = UUID.randomUUID()
        database.expenseDao().upsert(
            listOf(local(id, ownerA, SyncState.DIRTY, remoteId = id.toString()))
        )
        val repository = FakeRepository(null, fetchError = IllegalStateException("network down"))

        var threw = false
        try {
            coordinator(repository).synchronize()
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue(threw)
        // Untouched: still present, still DIRTY, and nothing was uploaded.
        val stored = database.expenseDao().byId(id, ownerA)
        assertEquals(SyncState.DIRTY, stored!!.syncState)
        assertTrue(repository.upserted.isEmpty())
        assertTrue(repository.deleted.isEmpty())
    }

    /**
     * The account-switch window: a fetch started as A resolving after a switch
     * to B must be refused, not reconciled against B's rows — which would
     * absence-delete every one of B's synced records.
     */
    @Test
    fun `a snapshot from another account is refused instead of applied`() = runTest {
        val id = UUID.randomUUID()
        database.expenseDao().upsert(
            listOf(local(id, ownerB, SyncState.SYNCED, remoteId = id.toString()))
        )
        // Snapshot belongs to A; the active owner is now B.
        val repository = FakeRepository(CompleteSnapshot.forTesting(emptyList(), ownerA))

        var threw = false
        try {
            coordinator(repository, owner = ownerB).synchronize()
        } catch (_: CompleteSnapshot.OwnerMismatchException) {
            threw = true
        }

        assertTrue("a foreign snapshot was applied", threw)
        assertTrue(
            "the other account's synced row was absence-deleted",
            database.expenseDao().byId(id, ownerB) != null
        )
    }

    /** An unproven snapshot may merge but must never delete. */
    @Test
    fun `an unproven snapshot inserts but does not absence-delete`() = runTest {
        val existing = UUID.randomUUID()
        val incoming = UUID.randomUUID()
        database.expenseDao().upsert(
            listOf(local(existing, ownerA, SyncState.SYNCED, remoteId = existing.toString()))
        )
        val repository = FakeRepository(
            CompleteSnapshot.forTesting(
                listOf(remote(incoming, ownerA)),
                ownerA,
                authorizesAbsenceDeletion = false
            )
        )

        val outcome = coordinator(repository).synchronize()

        assertEquals(1, outcome.inserted)
        assertEquals("an unproven snapshot authorised a deletion", 0, outcome.deletedLocally)
        assertTrue(database.expenseDao().byId(existing, ownerA) != null)
    }

    @Test
    fun `a tombstone is deleted remotely and only then removed locally`() = runTest {
        val id = UUID.randomUUID()
        database.expenseDao().upsert(
            listOf(local(id, ownerA, SyncState.DELETED, remoteId = id.toString()))
        )
        // Present remotely, so reconciliation does not delete it by absence.
        val repository = FakeRepository(
            CompleteSnapshot.forTesting(listOf(remote(id, ownerA)), ownerA)
        )

        val outcome = coordinator(repository).synchronize()

        assertEquals(1, outcome.deletedRemotely)
        assertEquals(listOf(id), repository.deleted)
        assertNull(database.expenseDao().byId(id, ownerA))
    }

    @Test
    fun `a signed-out cycle refuses to run`() = runTest {
        val repository = FakeRepository(CompleteSnapshot.forTesting(emptyList(), ownerA))

        var threw = false
        try {
            coordinator(repository, owner = null).synchronize()
        } catch (_: NotAuthenticatedException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(repository.upserted.isEmpty())
    }

    @Test
    fun `a quiet cycle reports failure as null rather than throwing`() = runTest {
        val repository = FakeRepository(null, fetchError = IllegalStateException("offline"))
        assertNull(coordinator(repository).synchronizeQuietly())
    }

    /**
     * Atomicity: the plan deletes and inserts together, so a failure partway
     * must leave NOTHING applied. Applied piecemeal, a crash between the two
     * looks like data loss to the user and like "deleted elsewhere" to the next
     * cycle.
     */
    @Test
    fun `an aborted transaction applies no part of the plan`() = runTest {
        val existing = UUID.randomUUID()
        database.expenseDao().upsert(
            listOf(local(existing, ownerA, SyncState.SYNCED, remoteId = existing.toString()))
        )

        val aborting = object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T =
                throw IllegalStateException("transaction aborted")
        }

        val repository = FakeRepository(
            CompleteSnapshot.forTesting(listOf(remote(UUID.randomUUID(), ownerA)), ownerA)
        )

        var threw = false
        try {
            ExpenseSyncCoordinator(
                dao = database.expenseDao(),
                repository = repository,
                transactions = aborting,
                activeOwnerId = { ownerA }
            ).synchronize()
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue(threw)
        assertEquals(1, database.expenseDao().allForOwner(ownerA).size)
        assertTrue(repository.upserted.isEmpty())
    }
}
