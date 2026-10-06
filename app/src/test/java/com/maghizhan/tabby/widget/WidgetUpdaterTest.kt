package com.maghizhan.tabby.widget

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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The widget's snapshot is refreshed and cleared by the app.
 *
 * Two defects met here. The widget read the shared Room cache unscoped, so it
 * totalled every account that had ever signed in — including after sign-out. And
 * nothing in production ever called an update, even though
 * `updatePeriodMillis="0"` means the app is the only thing that can: the widget
 * simply kept whatever it first rendered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetUpdaterTest {

    private lateinit var database: TabbyDatabase
    private lateinit var store: InMemoryWidgetSnapshotStore
    private lateinit var updater: WidgetUpdater
    private var hostNotifications = 0

    private val ownerA = "11111111-1111-1111-1111-111111111111"
    private val ownerB = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TabbyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = InMemoryWidgetSnapshotStore()
        hostNotifications = 0
        updater = WidgetUpdater(
            context = context,
            expenseDao = database.expenseDao(),
            store = store,
            notifyHost = { hostNotifications++ }
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun insert(owner: String, amount: String) {
        val now = Instant.now()
        database.expenseDao().upsert(
            listOf(
                ExpenseEntity(
                    id = UUID.randomUUID(),
                    amount = BigDecimal(amount),
                    categoryName = "Food",
                    note = null,
                    date = now,
                    createdAt = now,
                    updatedAt = now,
                    syncStateRaw = SyncState.SYNCED.raw,
                    remoteId = null,
                    ownerId = owner
                )
            )
        )
    }

    @Test
    fun `a signed-in owner gets only its own total`() = runTest {
        insert(ownerA, "10.00")
        insert(ownerB, "999.00")

        updater.setActiveOwner(ownerA)

        val snapshot = store.read()
        assertNotNull(snapshot)
        assertEquals(ownerA, snapshot!!.ownerId)
        assertEquals(BigDecimal("10.00"), snapshot.total(WidgetMode.DAY))
    }

    @Test
    fun `sign-out clears the snapshot so cached rows are not shown`() = runTest {
        insert(ownerA, "10.00")
        updater.setActiveOwner(ownerA)
        assertNotNull(store.read())

        updater.setActiveOwner(null)

        // The rows are STILL in the cache — that is exactly why clearing the
        // snapshot is what protects the home screen.
        assertEquals(1, database.expenseDao().allForOwner(ownerA).size)
        assertNull("a signed-out widget must have nothing to render", store.read())
    }

    @Test
    fun `a blank owner is treated as signed out`() = runTest {
        insert(ownerA, "10.00")
        updater.setActiveOwner(ownerA)

        updater.setActiveOwner("   ")

        assertNull(store.read())
    }

    @Test
    fun `an account change replaces rather than merges the snapshot`() = runTest {
        insert(ownerA, "10.00")
        insert(ownerB, "25.00")

        updater.setActiveOwner(ownerA)
        updater.setActiveOwner(ownerB)

        val snapshot = store.read()
        assertEquals(ownerB, snapshot?.ownerId)
        assertEquals(BigDecimal("25.00"), snapshot?.total(WidgetMode.DAY))
    }

    @Test
    fun `each refresh asks the host to redraw`() = runTest {
        updater.setActiveOwner(ownerA)
        updater.refresh()
        updater.setActiveOwner(null)

        assertEquals(3, hostNotifications)
    }

    @Test
    fun `a refresh picks up a newly committed local write`() = runTest {
        updater.setActiveOwner(ownerA)
        assertEquals(BigDecimal.ZERO, store.read()?.total(WidgetMode.DAY))

        insert(ownerA, "12.50")
        updater.refresh()

        assertEquals(BigDecimal("12.50"), store.read()?.total(WidgetMode.DAY))
    }

    @Test
    fun `a host that refuses the update still leaves a correct snapshot`() = runTest {
        val failing = WidgetUpdater(
            context = ApplicationProvider.getApplicationContext(),
            expenseDao = database.expenseDao(),
            store = store,
            notifyHost = { throw IllegalStateException("no widget placed") }
        )
        insert(ownerA, "3.00")

        // Must not throw: a failed redraw cannot be allowed to fail the user's
        // save or their sign-in.
        failing.setActiveOwner(ownerA)

        assertEquals(BigDecimal("3.00"), store.read()?.total(WidgetMode.DAY))
    }
}
