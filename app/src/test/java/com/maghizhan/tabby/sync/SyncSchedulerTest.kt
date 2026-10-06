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
        expenses: ExpenseRepositoring = Expenses(),
        onRunCompleted: suspend (String) -> Unit = {}
    ): SyncScheduler {
        val runner = RoomTransactionRunner(database)
        return SyncScheduler(
            expenses = ExpenseSyncCoordinator(database.expenseDao(), expenses, runner, sessions),
            categories = CategorySyncCoordinator(
                database.categoryDao(), Categories(), runner, sessions
            ),
            friends = FriendSyncCoordinator(database.friendDao(), Friends(), runner, sessions),
            sessions = sessions,
            onRunCompleted = onRunCompleted
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

    /**
     * Returning to the foreground must PULL.
     *
     * `onForegrounded` existed but had no production caller, so another device's
     * edits and deletes stayed invisible until the next authentication or
     * relaunch. The activity now drives it from a lifecycle observer; this pins
     * that the trigger itself runs a full cycle and still honours the
     * signed-out skip.
     */
    @Test
    fun `the foreground trigger runs a full cycle`() = runTest {
        val run = scheduler(FixedSessions(ActiveSession(owner, 1))).onForegrounded()

        assertEquals(listOf("categories", "friends", "expenses"), order)
        assertNull(run.skippedReason)
    }

    @Test
    fun `a foreground trigger while signed out is skipped rather than failing`() = runTest {
        val run = scheduler(FixedSessions(null)).onForegrounded()

        assertEquals("signed out", run.skippedReason)
        assertEquals(emptyList<String>(), order)
    }

    /**
     * A completed run is a moment the widget's numbers can have changed, because
     * reconciliation is where other devices' edits land. Nothing refreshed it
     * before, so the home screen kept whatever it first rendered.
     */
    @Test
    fun `a completed run reports the account so the widget can be refreshed`() = runTest {
        val refreshed = mutableListOf<String>()
        scheduler(
            FixedSessions(ActiveSession(owner, 1)),
            onRunCompleted = { refreshed += it }
        ).runNow()

        assertEquals(listOf(owner), refreshed)
    }

    @Test
    fun `a skipped run does not refresh the widget`() = runTest {
        val refreshed = mutableListOf<String>()
        scheduler(FixedSessions(null), onRunCompleted = { refreshed += it }).runNow()

        assertEquals(emptyList<String>(), refreshed)
    }

    @Test
    fun `the widget is refreshed after the local store reflects the run`() = runTest {
        // Ordering matters: refreshing before the applies would snapshot stale
        // numbers.
        val events = mutableListOf<String>()
        scheduler(
            FixedSessions(ActiveSession(owner, 1)),
            onRunCompleted = { events += "refresh" }
        ).runNow()

        assertEquals(listOf("categories", "friends", "expenses"), order)
        assertEquals(listOf("refresh"), events)
    }
}
