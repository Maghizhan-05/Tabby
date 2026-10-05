package com.maghizhan.tabby.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maghizhan.tabby.data.local.RoomTransactionRunner
import com.maghizhan.tabby.data.local.TabbyDatabase
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
import java.util.UUID

/**
 * The scheduler is what makes the coordinators reachable from production.
 *
 * Covered here: all three entity types actually run, in dependency order, and a
 * failure in one does not stop the others.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSchedulerTest {

    private lateinit var database: TabbyDatabase
    private val owner = "11111111-1111-1111-1111-111111111111"
    private val order = mutableListOf<String>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TabbyDatabase::class.java
        ).allowMainThreadQueries().build()
        order.clear()
    }

    @After
    fun tearDown() = database.close()

    private class FixedSessions(private val session: ActiveSession?) : SessionProvider {
        override suspend fun current(): ActiveSession? = session
    }

    private inner class Expenses(private val fail: Boolean = false) : ExpenseRepositoring {
        override suspend fun fetchAll(binding: SessionBinding): CompleteSnapshot<RemoteExpenseRow> {
            order += "expenses"
            if (fail) throw IllegalStateException("expenses down")
            return CompleteSnapshot.forTesting(emptyList(), binding.session)
        }
        override suspend fun upsert(rows: List<RemoteExpenseRow>) = Unit
        override suspend fun delete(ids: List<UUID>) = Unit
    }

    private inner class Categories : CategoryRepositoring {
        override suspend fun fetchAll(binding: SessionBinding): CompleteSnapshot<RemoteCategoryRow> {
            order += "categories"
            return CompleteSnapshot.forTesting(emptyList(), binding.session)
        }
        override suspend fun upsert(rows: List<RemoteCategoryRow>) = Unit
        override suspend fun delete(ids: List<UUID>) = Unit
    }

    private inner class Friends : FriendRepositoring {
        override suspend fun fetchAll(binding: SessionBinding): CompleteSnapshot<RemoteFriendRow> {
            order += "friends"
            return CompleteSnapshot.forTesting(emptyList(), binding.session)
        }
        override suspend fun upsert(rows: List<RemoteFriendRow>) = Unit
        override suspend fun delete(ids: List<UUID>) = Unit
    }

    private fun scheduler(
        sessions: SessionProvider,
        expenses: ExpenseRepositoring = Expenses()
    ): SyncScheduler {
        val runner = RoomTransactionRunner(database)
        return SyncScheduler(
            expenses = ExpenseSyncCoordinator(database.expenseDao(), expenses, runner, sessions),
            categories = CategorySyncCoordinator(
                database.categoryDao(), Categories(), runner, sessions
            ),
            friends = FriendSyncCoordinator(database.friendDao(), Friends(), runner, sessions),
            sessions = sessions
        )
    }

    @Test
    fun `a run syncs all three types in dependency order`() = runTest {
        val run = scheduler(FixedSessions(ActiveSession(owner, 1))).runNow()

        // Categories first: an expense references its category by name, so the
        // reverse order shows an expense whose category is briefly missing.
        assertEquals(listOf("categories", "friends", "expenses"), order)
        assertNotNull(run.categories)
        assertNotNull(run.friends)
        assertNotNull(run.expenses)
        assertNull(run.skippedReason)
    }

    @Test
    fun `one failing type does not stop the others`() = runTest {
        val run = scheduler(
            FixedSessions(ActiveSession(owner, 1)),
            expenses = Expenses(fail = true)
        ).runNow()

        assertNotNull("categories must still sync", run.categories)
        assertNotNull("friends must still sync", run.friends)
        assertNull("the failing type reports null", run.expenses)
    }

    @Test
    fun `a signed-out run is skipped rather than failing`() = runTest {
        val run = scheduler(FixedSessions(null)).runNow()

        assertEquals("signed out", run.skippedReason)
        assertEquals(emptyList<String>(), order)
    }

    @Test
    fun `the authenticated trigger runs a full cycle`() = runTest {
        val run = scheduler(FixedSessions(ActiveSession(owner, 1))).onAuthenticated()
        assertEquals(listOf("categories", "friends", "expenses"), order)
        assertNull(run.skippedReason)
    }
}
