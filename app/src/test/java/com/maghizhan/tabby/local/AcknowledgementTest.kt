package com.maghizhan.tabby.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maghizhan.tabby.data.local.TabbyDatabase
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
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
 * Compare-and-set acknowledgement and legacy-row claiming, against a real
 * database.
 *
 * These cover the two races an id-and-owner-only `markSynced` could not:
 * an edit made while an upload is in flight, and a row left with a null owner
 * that any later account could pick up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AcknowledgementTest {

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

    private fun row(
        id: UUID = UUID.randomUUID(),
        owner: String? = ownerA,
        state: SyncState = SyncState.DIRTY,
        revision: Int = 3
    ) = ExpenseEntity(
        id = id,
        amount = BigDecimal("12.34"),
        categoryName = "Coffee",
        note = null,
        date = Instant.EPOCH,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        syncStateRaw = state.raw,
        remoteId = null,
        ownerId = owner,
        revision = revision
    )

    @Test
    fun `acknowledgement succeeds when the row is unchanged`() = runTest {
        val dao = database.expenseDao()
        val entity = row()
        dao.upsert(listOf(entity))

        val affected = dao.markSyncedIfUnchanged(entity.id, ownerA, 3, SyncState.DIRTY.raw)

        assertEquals(1, affected)
        assertEquals(SyncState.SYNCED, dao.byIdUnscoped(entity.id)?.syncState)
    }

    @Test
    fun `an edit during upload is NOT cleared by acknowledgement`() = runTest {
        val dao = database.expenseDao()
        val entity = row(revision = 3)
        dao.upsert(listOf(entity))

        // The user edits while the upload is in flight; the store bumps revision.
        dao.upsert(listOf(entity.copy(amount = BigDecimal("99.99"), revision = 4)))

        // The in-flight acknowledgement carries the OLD revision.
        val affected = dao.markSyncedIfUnchanged(entity.id, ownerA, 3, SyncState.DIRTY.raw)

        assertEquals("a changed row must not be acknowledged", 0, affected)
        val current = dao.byIdUnscoped(entity.id)
        assertEquals(
            "the edit must stay pending so it is pushed next cycle",
            SyncState.DIRTY,
            current?.syncState
        )
        assertEquals(BigDecimal("99.99"), current?.amount)
    }

    @Test
    fun `acknowledgement refuses when the expected state no longer matches`() = runTest {
        val dao = database.expenseDao()
        val entity = row(state = SyncState.DIRTY, revision = 3)
        dao.upsert(listOf(entity))

        // Deleted during the upload, revision unchanged by the state flip alone.
        dao.upsert(listOf(entity.copy(syncStateRaw = SyncState.DELETED.raw)))

        val affected = dao.markSyncedIfUnchanged(entity.id, ownerA, 3, SyncState.DIRTY.raw)

        assertEquals(0, affected)
        assertEquals(SyncState.DELETED, dao.byIdUnscoped(entity.id)?.syncState)
    }

    @Test
    fun `acknowledgement refuses for a different owner`() = runTest {
        val dao = database.expenseDao()
        val entity = row()
        dao.upsert(listOf(entity))

        val affected = dao.markSyncedIfUnchanged(entity.id, ownerB, 3, SyncState.DIRTY.raw)

        assertEquals(0, affected)
        assertEquals(SyncState.DIRTY, dao.byIdUnscoped(entity.id)?.syncState)
    }

    @Test
    fun `a tombstone changed during delete is NOT physically removed`() = runTest {
        val dao = database.expenseDao()
        val entity = row(state = SyncState.DELETED, revision = 2)
        dao.upsert(listOf(entity))

        // Resurrected mid-delete (the user undid it), revision bumped.
        dao.upsert(listOf(entity.copy(syncStateRaw = SyncState.DIRTY.raw, revision = 3)))

        val affected = dao.deleteTombstoneIfUnchanged(entity.id, ownerA, 2)

        assertEquals(0, affected)
        assertNotNull("the resurrected row must survive", dao.byIdUnscoped(entity.id))
    }

    @Test
    fun `an unchanged tombstone is removed`() = runTest {
        val dao = database.expenseDao()
        val entity = row(state = SyncState.DELETED, revision = 2)
        dao.upsert(listOf(entity))

        assertEquals(1, dao.deleteTombstoneIfUnchanged(entity.id, ownerA, 2))
        assertNull(dao.byIdUnscoped(entity.id))
    }

    @Test
    fun `claiming adopts null-owner rows for the cycle owner`() = runTest {
        val dao = database.expenseDao()
        val legacy = row(owner = null)
        dao.upsert(listOf(legacy))

        val claimed = dao.claimLegacyRows(ownerA)

        assertEquals(1, claimed)
        assertEquals(ownerA, dao.byIdUnscoped(legacy.id)?.ownerId)
    }

    @Test
    fun `a claimed row is invisible to another account`() = runTest {
        val dao = database.expenseDao()
        val legacy = row(owner = null)
        dao.upsert(listOf(legacy))
        dao.claimLegacyRows(ownerA)

        // Before claiming, a null-owner row was readable by whoever asked.
        assertTrue(dao.allForOwner(ownerB).isEmpty())
        assertTrue(dao.pendingPush(ownerB).isEmpty())
        assertEquals(1, dao.allForOwner(ownerA).size)
    }

    @Test
    fun `claiming does not touch rows already owned by someone else`() = runTest {
        val dao = database.expenseDao()
        val theirs = row(owner = ownerB)
        dao.upsert(listOf(theirs))

        assertEquals(0, dao.claimLegacyRows(ownerA))
        assertEquals(ownerB, dao.byIdUnscoped(theirs.id)?.ownerId)
    }

    @Test
    fun `owner-scoped reads exclude null-owner rows`() = runTest {
        val dao = database.expenseDao()
        dao.upsert(listOf(row(owner = null)))

        // Unclaimed legacy rows are nobody's until a cycle claims them; treating
        // them as readable is what let another account process them.
        assertTrue(dao.allForOwner(ownerA).isEmpty())
    }
}
