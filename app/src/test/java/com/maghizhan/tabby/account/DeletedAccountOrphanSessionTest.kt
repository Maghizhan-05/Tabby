package com.maghizhan.tabby.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maghizhan.tabby.data.local.RoomTransactionRunner
import com.maghizhan.tabby.data.local.TabbyDatabase
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.local.entity.FriendEntity
import com.maghizhan.tabby.data.remote.ActiveSession
import com.maghizhan.tabby.data.remote.CategoryRepositoring
import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.ExpenseRepositoring
import com.maghizhan.tabby.data.remote.FriendRepositoring
import com.maghizhan.tabby.data.remote.SessionBinding
import com.maghizhan.tabby.data.remote.SessionProvider
import com.maghizhan.tabby.data.remote.model.RemoteCategoryRow
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.remote.model.RemoteFriendRow
import com.maghizhan.tabby.data.sync.CategorySyncCoordinator
import com.maghizhan.tabby.data.sync.ExpenseSyncCoordinator
import com.maghizhan.tabby.data.sync.FriendSyncCoordinator
import com.maghizhan.tabby.data.sync.SyncScheduler
import com.maghizhan.tabby.data.sync.SyncState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The state a SECOND device is left in after the account is deleted elsewhere.
 *
 * Deleting an `auth.users` row does not revoke already-issued access tokens —
 * Supabase's managing-user-data guidance is explicit that the JWT stays valid
 * until it expires. So another signed-in device keeps a structurally valid
 * session pointing at an account whose rows have all been cascaded away.
 *
 * The device that performed the deletion is unaffected (it clears its own
 * session), but this one has nobody to tell it. The required behaviour is that
 * it degrades to an empty, usable app: every sync run must complete normally,
 * the orphaned local rows must be removed rather than resurrect forever, and
 * repeated runs must stay stable instead of throwing on each pass — a crash
 * loop here would be a far worse outcome than an empty screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeletedAccountOrphanSessionTest {

    private lateinit var database: TabbyDatabase
    private val owner = "11111111-1111-1111-1111-111111111111"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TabbyDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private class FixedSessions(private val session: ActiveSession?) : SessionProvider {
        override suspend fun current(): ActiveSession? = session
    }

    /** Everything cascaded away server-side: the account's rows are simply gone. */
    private class EmptyExpenses : ExpenseRepositoring {
        override suspend fun fetchAll(binding: SessionBinding) =
            CompleteSnapshot.forTesting(emptyList<RemoteExpenseRow>(), binding.session)
        override suspend fun upsert(rows: List<RemoteExpenseRow>) = Unit
        override suspend fun delete(ids: List<UUID>) = Unit
    }

    private class EmptyCategories : CategoryRepositoring {
        override suspend fun fetchAll(binding: SessionBinding) =
            CompleteSnapshot.forTesting(emptyList<RemoteCategoryRow>(), binding.session)
        override suspend fun upsert(rows: List<RemoteCategoryRow>) = Unit
        override suspend fun delete(ids: List<UUID>) = Unit
    }

    private class EmptyFriends : FriendRepositoring {
        override suspend fun fetchAll(binding: SessionBinding) =
            CompleteSnapshot.forTesting(emptyList<RemoteFriendRow>(), binding.session)
        override suspend fun upsert(rows: List<RemoteFriendRow>) = Unit
        override suspend fun delete(ids: List<UUID>) = Unit
    }

    private fun scheduler(): SyncScheduler {
        val sessions = FixedSessions(ActiveSession(owner, 1))
        val runner = RoomTransactionRunner(database)
        return SyncScheduler(
            expenses = ExpenseSyncCoordinator(
                database.expenseDao(), EmptyExpenses(), runner, sessions
            ),
            categories = CategorySyncCoordinator(
                database.categoryDao(), EmptyCategories(), runner, sessions
            ),
            friends = FriendSyncCoordinator(
                database.friendDao(), EmptyFriends(), runner, sessions
            ),
            sessions = sessions
        )
    }

    private suspend fun seedSyncedRows() {
        database.expenseDao().upsert(
            listOf(
                ExpenseEntity(
                    id = UUID.randomUUID(),
                    amount = BigDecimal("42.00"),
                    categoryName = "Food",
                    note = "cached before the account was deleted",
                    date = Instant.EPOCH,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    syncStateRaw = SyncState.SYNCED.raw,
                    remoteId = UUID.randomUUID().toString(),
                    ownerId = owner,
                    revision = 1
                )
            )
        )
        database.categoryDao().upsert(
            listOf(
                CategoryEntity(
                    id = UUID.randomUUID(),
                    name = "Food",
                    isDefault = false,
                    sortOrder = 0,
                    syncStateRaw = SyncState.SYNCED.raw,
                    remoteId = UUID.randomUUID().toString(),
                    ownerId = owner,
                    revision = 1
                )
            )
        )
        database.friendDao().upsert(
            listOf(
                FriendEntity(
                    id = UUID.randomUUID(),
                    name = "Example Friend",
                    theyOweUs = BigDecimal("0.00"),
                    weOweThem = BigDecimal("0.00"),
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    syncStateRaw = SyncState.SYNCED.raw,
                    remoteId = UUID.randomUUID().toString(),
                    ownerId = owner,
                    revision = 1
                )
            )
        )
    }

    @Test
    fun `a session outliving its deleted account syncs cleanly instead of failing`() = runTest {
        seedSyncedRows()

        val run = scheduler().runNow()

        // No type reports failure: a deleted account looks exactly like an
        // account with no data, which is a state sync already handles.
        assertNull(run.skippedReason)
        assertNotNull("expenses must not fail", run.expenses)
        assertNotNull("categories must not fail", run.categories)
        assertNotNull("friends must not fail", run.friends)
    }

    @Test
    fun `the stale local dataset is cleared rather than left on screen`() = runTest {
        seedSyncedRows()

        scheduler().runNow()

        assertEquals(0, database.expenseDao().allForOwner(owner).size)
        assertEquals(0, database.categoryDao().allForOwner(owner).size)
        assertEquals(0, database.friendDao().allForOwner(owner).size)
    }

    @Test
    fun `repeated runs on the orphaned session stay stable`() = runTest {
        seedSyncedRows()
        val scheduler = scheduler()

        // Three passes: the foregrounding path re-runs on every return to the
        // app, so an error that only surfaces after the first clean-up would
        // become a loop the user cannot escape.
        repeat(3) {
            val run = scheduler.runNow()
            assertNull(run.skippedReason)
            assertNotNull(run.expenses)
            assertNotNull(run.categories)
            assertNotNull(run.friends)
        }

        assertEquals(0, database.expenseDao().allForOwner(owner).size)
    }
}
