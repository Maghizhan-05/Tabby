package com.maghizhan.tabby.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maghizhan.tabby.data.local.ExpenseDao
import com.maghizhan.tabby.data.local.RoomTransactionRunner
import com.maghizhan.tabby.data.local.TabbyDatabase
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.remote.ActiveSession
import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.ExpenseRepositoring
import com.maghizhan.tabby.data.remote.SessionBinding
import com.maghizhan.tabby.data.remote.SessionChangedException
import com.maghizhan.tabby.data.remote.NotAuthenticatedException
import com.maghizhan.tabby.data.remote.SessionProvider
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.sync.ExpenseSyncCoordinator
import com.maghizhan.tabby.data.sync.SyncState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * The production coordinator, with REAL DAOs and repository fakes.
 *
 * The fakes are what make the races testable: a real backend cannot be told to
 * switch accounts between the pull and the push, which is precisely the window
 * these tests target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpenseSyncCoordinatorTest {

    private lateinit var database: TabbyDatabase
    private val ownerA = "11111111-1111-1111-1111-111111111111"
    private val ownerB = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TabbyDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    /** A session provider whose answer the test can change mid-cycle. */
    private class MutableSessions(var session: ActiveSession?) : SessionProvider {
        override suspend fun current(): ActiveSession? = session
    }

    private class FakeRepository(
        var snapshot: (SessionBinding) -> CompleteSnapshot<RemoteExpenseRow> = {
            CompleteSnapshot.forTesting(emptyList(), it.session)
        },
        var onFetch: () -> Unit = {},
        var onUpsert: (List<RemoteExpenseRow>) -> Unit = {},
        var onDelete: (List<UUID>) -> Unit = {}
    ) : ExpenseRepositoring {
        val upserted = mutableListOf<RemoteExpenseRow>()
        val deleted = mutableListOf<UUID>()

        override suspend fun fetchAll(binding: SessionBinding): CompleteSnapshot<RemoteExpenseRow> {
            onFetch()
            return snapshot(binding)
        }

        override suspend fun upsert(rows: List<RemoteExpenseRow>) {
            onUpsert(rows)
            upserted += rows
        }

        override suspend fun delete(ids: List<UUID>) {
            onDelete(ids)
            deleted += ids
        }
    }

    private fun coordinator(
        repository: ExpenseRepositoring,
        sessions: SessionProvider
    ) = ExpenseSyncCoordinator(
        dao = database.expenseDao(),
        repository = repository,
        transactions = RoomTransactionRunner(database),
        sessions = sessions
    )

    private fun local(
        id: UUID = UUID.randomUUID(),
        owner: String? = ownerA,
        state: SyncState = SyncState.DIRTY,
        revision: Int = 1,
        remoteId: String? = null
    ) = ExpenseEntity(
        id = id,
        amount = BigDecimal("10.00"),
        categoryName = "Coffee",
        note = null,
        date = Instant.EPOCH,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        syncStateRaw = state.raw,
        remoteId = remoteId,
        ownerId = owner,
        revision = revision
    )

    private fun remote(id: UUID, owner: String = ownerA) = RemoteExpenseRow(
        id = id,
        userId = owner,
        amount = BigDecimal("10.00"),
        categoryName = "Coffee",
        note = null,
        date = Instant.EPOCH,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH
    )

    @Test
    fun `a pull failure neither mutates locally nor pushes`() = runTest {
        val pending = local()
        database.expenseDao().upsert(listOf(pending))

        val repository = FakeRepository(onFetch = { throw IllegalStateException("network down") })
        val result = runCatching {
            coordinator(repository, MutableSessions(ActiveSession(ownerA, 1))).synchronize()
        }

        assertTrue(result.isFailure)
        assertTrue("nothing may be uploaded after a failed pull", repository.upserted.isEmpty())
        val row = database.expenseDao().byIdUnscoped(pending.id)
        assertEquals("the local row must be untouched", SyncState.DIRTY, row?.syncState)
        assertEquals(1, row?.revision)
    }

    @Test
    fun `an account switch between pull and push aborts before uploading`() = runTest {
        val pending = local()
        database.expenseDao().upsert(listOf(pending))

        val sessions = MutableSessions(ActiveSession(ownerA, 1))
        val repository = FakeRepository(
            snapshot = { CompleteSnapshot.forTesting(emptyList(), it.session) },
            // The user signs out of A and into B while the fetch is suspended.
            onFetch = { sessions.session = ActiveSession(ownerB, 2) }
        )

        val result = runCatching { coordinator(repository, sessions).synchronize() }

        assertTrue(result.exceptionOrNull() is SessionChangedException)
        assertTrue(
            "A's rows must never be uploaded under B's session",
            repository.upserted.isEmpty()
        )
        assertEquals(SyncState.DIRTY, database.expenseDao().byIdUnscoped(pending.id)?.syncState)
    }

    @Test
    fun `an account switch during the upload aborts before acknowledgement`() = runTest {
        val pending = local()
        database.expenseDao().upsert(listOf(pending))

        val sessions = MutableSessions(ActiveSession(ownerA, 1))
        val repository = FakeRepository(
            // The switch happens after the remote write, before the local ack.
            onUpsert = { sessions.session = ActiveSession(ownerB, 2) }
        )

        val result = runCatching { coordinator(repository, sessions).synchronize() }

        assertTrue(result.exceptionOrNull() is SessionChangedException)
        assertEquals(
            "the row must stay pending rather than be marked synced under B",
            SyncState.DIRTY,
            database.expenseDao().byIdUnscoped(pending.id)?.syncState
        )
    }

    @Test
    fun `the same user id in a new session generation still aborts`() = runTest {
        database.expenseDao().upsert(listOf(local()))

        val sessions = MutableSessions(ActiveSession(ownerA, 1))
        val repository = FakeRepository(
            // Signed out and back into the SAME account: identical owner id,
            // different session. The owner id alone cannot detect this.
            onFetch = { sessions.session = ActiveSession(ownerA, 2) }
        )

        val result = runCatching { coordinator(repository, sessions).synchronize() }

        assertTrue(result.exceptionOrNull() is SessionChangedException)
        assertTrue(repository.upserted.isEmpty())
    }

    @Test
    fun `signing out mid-cycle aborts`() = runTest {
        database.expenseDao().upsert(listOf(local()))

        val sessions = MutableSessions(ActiveSession(ownerA, 1))
        val repository = FakeRepository(onFetch = { sessions.session = null })

        val result = runCatching { coordinator(repository, sessions).synchronize() }

        assertTrue(result.exceptionOrNull() is SessionChangedException)
        assertTrue(repository.upserted.isEmpty())
    }

    @Test
    fun `a full cycle pulls then pushes and acknowledges`() = runTest {
        val remoteId = UUID.randomUUID()
        val pending = local(state = SyncState.DIRTY)
        database.expenseDao().upsert(listOf(pending))

        val order = mutableListOf<String>()
        val repository = FakeRepository(
            snapshot = {
                CompleteSnapshot.forTesting(listOf(remote(remoteId)), it.session)
            },
            onFetch = { order += "pull" },
            onUpsert = { order += "push" }
        )

        val outcome = coordinator(repository, MutableSessions(ActiveSession(ownerA, 1)))
            .synchronize()

        // Pull strictly before push: a push-first order would upload a row the
        // backend has already deleted, resurrecting it.
        assertEquals(listOf("pull", "push"), order)
        assertEquals(1, outcome.inserted)
        assertEquals(1, outcome.pushed)
        assertNotNull(database.expenseDao().byIdUnscoped(remoteId))
        assertEquals(SyncState.SYNCED, database.expenseDao().byIdUnscoped(pending.id)?.syncState)
    }

    @Test
    fun `an unproven snapshot merges but withholds deletion`() = runTest {
        val synced = local(state = SyncState.SYNCED, remoteId = UUID.randomUUID().toString())
        database.expenseDao().upsert(listOf(synced))

        val repository = FakeRepository(
            snapshot = { CompleteSnapshot.forTesting(emptyList(), it.session, authorizesAbsenceDeletion = false) }
        )

        val outcome = coordinator(repository, MutableSessions(ActiveSession(ownerA, 1)))
            .synchronize()

        assertEquals(0, outcome.deletedLocally)
        assertNotNull(outcome.deletionWithheldReason)
        assertNotNull(
            "an unproven absence must not destroy a synced row",
            database.expenseDao().byIdUnscoped(synced.id)
        )
    }

    @Test
    fun `a proven snapshot absence-deletes a previously uploaded row`() = runTest {
        val synced = local(state = SyncState.SYNCED, remoteId = UUID.randomUUID().toString())
        database.expenseDao().upsert(listOf(synced))

        val repository = FakeRepository(
            snapshot = { CompleteSnapshot.forTesting(emptyList(), it.session) }
        )

        val outcome = coordinator(repository, MutableSessions(ActiveSession(ownerA, 1)))
            .synchronize()

        assertEquals(1, outcome.deletedLocally)
        assertNull(database.expenseDao().byIdUnscoped(synced.id))
    }

    @Test
    fun `a never-uploaded row survives a proven empty snapshot`() = runTest {
        val neverUploaded = local(state = SyncState.LOCAL, remoteId = null)
        database.expenseDao().upsert(listOf(neverUploaded))

        val repository = FakeRepository(
            snapshot = { CompleteSnapshot.forTesting(emptyList(), it.session) }
        )

        coordinator(repository, MutableSessions(ActiveSession(ownerA, 1))).synchronize()

        assertNotNull(
            "absence tells us nothing about a row the backend never saw",
            database.expenseDao().byIdUnscoped(neverUploaded.id)
        )
    }

    @Test
    fun `a tombstone still present remotely is deleted remotely then locally`() = runTest {
        val id = UUID.randomUUID()
        val tombstone = local(id = id, state = SyncState.DELETED, remoteId = id.toString())
        database.expenseDao().upsert(listOf(tombstone))

        // The row is STILL on the backend, which is the case where a tombstone
        // has to be pushed. (When the snapshot proves it is already gone
        // remotely, reconciliation removes it locally and there is nothing to
        // push — covered separately below.)
        val repository = FakeRepository(
            snapshot = { CompleteSnapshot.forTesting(listOf(remote(id)), it.session) }
        )
        val outcome = coordinator(repository, MutableSessions(ActiveSession(ownerA, 1)))
            .synchronize()

        assertEquals(listOf(id), repository.deleted)
        assertEquals(1, outcome.deletedRemotely)
        assertNull(database.expenseDao().byIdUnscoped(id))
    }

    @Test
    fun `a tombstone already absent remotely is dropped without a remote call`() = runTest {
        val id = UUID.randomUUID()
        database.expenseDao().upsert(
            listOf(local(id = id, state = SyncState.DELETED, remoteId = id.toString()))
        )

        val repository = FakeRepository()
        coordinator(repository, MutableSessions(ActiveSession(ownerA, 1))).synchronize()

        // Deleting what the backend has already deleted is a pointless round
        // trip; the proven snapshot settles it locally.
        assertTrue(repository.deleted.isEmpty())
        assertNull(database.expenseDao().byIdUnscoped(id))
    }

    @Test
    fun `a signed-out cycle cannot start`() = runTest {
        val result = runCatching {
            coordinator(FakeRepository(), MutableSessions(null)).synchronize()
        }
        assertTrue(result.exceptionOrNull() is NotAuthenticatedException)
    }

    /**
     * The reconciliation apply is ONE transaction.
     *
     * A plan can insert, update and delete in the same cycle. Applied without a
     * transaction, a failure partway leaves the local store in a state that
     * matches neither the backend nor what the user had — rows inserted, their
     * counterpart deletions never applied. A rolled-back cycle simply runs again.
     *
     * Driven through a DAO that fails on the deletion step, so the failure lands
     * AFTER a real insert has already been written inside the transaction.
     */
    @Test
    fun `a failure midway through the apply rolls back the whole plan`() = runTest {
        val insertId = UUID.randomUUID()
        val toDelete = local(
            state = SyncState.SYNCED,
            remoteId = UUID.randomUUID().toString()
        )
        database.expenseDao().upsert(listOf(toDelete))

        // Proven snapshot containing a NEW row and omitting the synced one:
        // the plan therefore has both an insert and an absence deletion.
        val repository = FakeRepository(
            snapshot = { CompleteSnapshot.forTesting(listOf(remote(insertId)), it.session) }
        )

        val failingDao = object : ExpenseDao {
            private val real = database.expenseDao()
            override fun observeVisible(ownerId: String, deletedState: Int) =
                real.observeVisible(ownerId, deletedState)
            override suspend fun byId(id: UUID, ownerId: String) = real.byId(id, ownerId)
            override suspend fun byIdUnscoped(id: UUID) = real.byIdUnscoped(id)
            override suspend fun allForOwner(ownerId: String) = real.allForOwner(ownerId)
            override suspend fun pendingPush(ownerId: String, syncedState: Int) =
                real.pendingPush(ownerId, syncedState)
            override suspend fun claimLegacyRows(ownerId: String) = real.claimLegacyRows(ownerId)
            override suspend fun upsert(expenses: List<ExpenseEntity>) = real.upsert(expenses)
            override suspend fun markSyncedIfUnchanged(
                id: UUID,
                ownerId: String,
                expectedRevision: Int,
                expectedState: Int,
                syncedState: Int
            ) = real.markSyncedIfUnchanged(id, ownerId, expectedRevision, expectedState, syncedState)
            override suspend fun deleteTombstoneIfUnchanged(
                id: UUID,
                ownerId: String,
                expectedRevision: Int,
                expectedState: Int
            ) = real.deleteTombstoneIfUnchanged(id, ownerId, expectedRevision, expectedState)

            /** Fails after the insert has already been applied in-transaction. */
            override suspend fun deleteByIds(ids: List<UUID>, ownerId: String) {
                throw IllegalStateException("delete step failed")
            }
        }

        val failure = runCatching {
            ExpenseSyncCoordinator(
                dao = failingDao,
                repository = repository,
                transactions = RoomTransactionRunner(database),
                sessions = MutableSessions(ActiveSession(ownerA, 1))
            ).synchronize()
        }

        assertTrue(failure.isFailure)
        assertNull(
            "the inserted row must be rolled back with the failed plan",
            database.expenseDao().byIdUnscoped(insertId)
        )
        assertNotNull(
            "the row the plan would have deleted must still be here",
            database.expenseDao().byIdUnscoped(toDelete.id)
        )
        assertTrue("nothing may be pushed after a failed apply", repository.upserted.isEmpty())
    }

    @Test
    fun `legacy null-owner rows are claimed and pushed under the active account`() = runTest {
        val legacy = local(owner = null, state = SyncState.LOCAL)
        database.expenseDao().upsert(listOf(legacy))

        val repository = FakeRepository()
        val outcome = coordinator(repository, MutableSessions(ActiveSession(ownerA, 1)))
            .synchronize()

        assertEquals(1, outcome.claimedLegacy)
        assertEquals(ownerA, database.expenseDao().byIdUnscoped(legacy.id)?.ownerId)
        assertEquals(1, repository.upserted.size)
    }
}
