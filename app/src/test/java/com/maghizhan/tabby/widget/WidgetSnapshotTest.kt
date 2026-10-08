package com.maghizhan.tabby.widget

import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * The widget must never render another account's spending.
 *
 * The defect these cover: the widget read the shared Room cache through an
 * unscoped `allVisible()` query, which totalled EVERY account that had ever
 * signed in on the device — and kept doing so after sign-out, because the cached
 * rows are still there. The fix is a per-owner sanitised snapshot that the app
 * writes and clears; the tests below pin both the filtering and the clearing.
 */
class WidgetSnapshotTest {

    private val ownerA = "11111111-1111-1111-1111-111111111111"
    private val ownerB = "22222222-2222-2222-2222-222222222222"
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-01-15T06:00:00Z")

    private fun expense(
        owner: String?,
        amount: String,
        category: String = "Food",
        date: Instant = now,
        state: SyncState = SyncState.SYNCED
    ) = ExpenseEntity(
        id = UUID.randomUUID(),
        amount = BigDecimal(amount),
        categoryName = category,
        note = null,
        date = date,
        createdAt = date,
        updatedAt = date,
        syncStateRaw = state.raw,
        remoteId = null,
        ownerId = owner
    )

    private fun build(owner: String, rows: List<ExpenseEntity>) =
        WidgetSnapshotFactory.build(owner, rows, now, zone, Locale.UK)

    @Test
    fun `another account's expenses are excluded from every period total`() {
        val snapshot = build(
            ownerA,
            listOf(
                expense(ownerA, "10.00"),
                expense(ownerB, "999.00"),
                // A legacy unowned row is nobody's until claimed, so it is not
                // this account's to display either.
                expense(null, "500.00")
            )
        )

        for (mode in WidgetMode.entries) {
            assertEquals(
                "${mode.name} total leaked another account's spending",
                BigDecimal("10.00"),
                snapshot.total(mode)
            )
        }
    }

    @Test
    fun `tombstoned expenses are not counted`() {
        val snapshot = build(
            ownerA,
            listOf(
                expense(ownerA, "10.00"),
                expense(ownerA, "40.00", state = SyncState.DELETED)
            )
        )
        assertEquals(BigDecimal("10.00"), snapshot.total(WidgetMode.DAY))
    }

    @Test
    fun `owner matching is case-insensitive like every other owner predicate`() {
        val snapshot = build(ownerA.uppercase(), listOf(expense(ownerA, "7.50")))
        assertEquals(BigDecimal("7.50"), snapshot.total(WidgetMode.DAY))
    }

    @Test
    fun `a blank owner yields the signed-out snapshot rather than everything`() {
        val snapshot = WidgetSnapshotFactory.build(
            ownerId = "   ",
            expenses = listOf(expense(ownerA, "10.00"), expense(ownerB, "20.00")),
            now = now,
            zone = zone
        )

        assertEquals("", snapshot.ownerId)
        assertEquals(BigDecimal.ZERO, snapshot.total(WidgetMode.DAY))
        assertTrue(snapshot.categorySlices.isEmpty())
    }

    @Test
    fun `period totals widen correctly from day to month`() {
        // 06:00 UTC on the 15th is 11:30 local, so all three rows below sit in
        // the same local month and the day/week boundaries are what separate them.
        val snapshot = build(
            ownerA,
            listOf(
                expense(ownerA, "10.00", date = now),
                expense(ownerA, "20.00", date = Instant.parse("2026-01-13T06:00:00Z")),
                expense(ownerA, "30.00", date = Instant.parse("2026-01-05T06:00:00Z"))
            )
        )

        assertEquals(BigDecimal("10.00"), snapshot.total(WidgetMode.DAY))
        // Locale.UK weeks start Monday; the 13th and 15th are the same week.
        assertEquals(BigDecimal("30.00"), snapshot.total(WidgetMode.WEEK))
        assertEquals(BigDecimal("60.00"), snapshot.total(WidgetMode.MONTH))
    }

    @Test
    fun `the category breakdown aggregates the tail so slices sum to the month total`() {
        val rows = listOf(
            expense(ownerA, "50.00", category = "Food"),
            expense(ownerA, "40.00", category = "Transport"),
            expense(ownerA, "30.00", category = "Bills"),
            expense(ownerA, "20.00", category = "Health"),
            expense(ownerA, "10.00", category = "Pets"),
            expense(ownerA, "5.00", category = "Gifts")
        )
        val snapshot = build(ownerA, rows)

        assertEquals(WidgetSnapshot.MAXIMUM_SLICES + 1, snapshot.categorySlices.size)
        assertEquals("Other", snapshot.categorySlices.last().category)
        assertEquals(BigDecimal("15.00"), snapshot.categorySlices.last().total)

        val slicesTotal = snapshot.categorySlices.fold(BigDecimal.ZERO) { sum, s -> sum.add(s.total) }
        assertEquals(
            "listed slices must sum to the displayed month total",
            snapshot.total(WidgetMode.MONTH),
            slicesTotal
        )
    }

    @Test
    fun `an empty period is empty even when another period has spending`() {
        // The widget's empty state is per MODE, as on iOS: a quiet day shows
        // "No spending yet" rather than ₹0 while the month still totals.
        val lastMonth = Instant.parse("2025-12-20T06:00:00Z")
        val snapshot = build(ownerA, listOf(expense(ownerA, "80.00", date = lastMonth)))

        assertTrue("today had no spending", snapshot.isEmpty(WidgetMode.DAY))
        assertTrue("this month had no spending", snapshot.isEmpty(WidgetMode.MONTH))
    }

    @Test
    fun `a period with spending is not empty`() {
        val snapshot = build(ownerA, listOf(expense(ownerA, "10.00")))

        for (mode in WidgetMode.entries) {
            assertFalse(
                "${mode.name} must render its total, not the empty state",
                snapshot.isEmpty(mode)
            )
        }
    }

    @Test
    fun `categories is empty when no slice is positive`() {
        // Drives the CATEGORIES branch specifically: it reads the slices, not a
        // period total, so a month total without slices must still be empty.
        val snapshot = WidgetSnapshot(
            ownerId = ownerA,
            periodTotals = mapOf(WidgetMode.MONTH.name to "25.00"),
            categorySlices = emptyList()
        )
        assertTrue(snapshot.isEmpty(WidgetMode.CATEGORIES))
        assertFalse(snapshot.isEmpty(WidgetMode.MONTH))
    }

    @Test
    fun `the signed-out snapshot is empty in every mode`() {
        for (mode in WidgetMode.entries) {
            assertTrue(WidgetSnapshot.SIGNED_OUT.isEmpty(mode))
        }
    }

    @Test
    fun `every mode carries its own ring slices`() {
        // iOS draws a ring in every presentation from that period's slices. With
        // only the month's slices stored, TODAY and THIS WEEK had nothing to draw
        // and fell back to a bare number — the most visible widget difference.
        val lastMonth = Instant.parse("2025-12-10T06:00:00Z")
        val snapshot = WidgetSnapshotFactory.build(
            ownerId = ownerA,
            expenses = listOf(
                expense(ownerA, "500", category = "Food"),
                expense(ownerA, "250", category = "Transport"),
                expense(ownerA, "900", category = "Rent", date = lastMonth)
            ),
            now = now,
            zone = zone,
            locale = Locale.US
        )

        val today = snapshot.slices(WidgetMode.DAY)
        assertEquals(setOf("Food", "Transport"), today.map { it.category }.toSet())
        assertEquals(BigDecimal("750"), today.fold(BigDecimal.ZERO) { s, it -> s.add(it.total) })

        // Last month's rent is outside every stored period, so no ring carries it.
        assertTrue(snapshot.slices(WidgetMode.MONTH).none { it.category == "Rent" })
    }

    @Test
    fun `an old snapshot without per-mode slices still draws its category ring`() {
        // Deserialized from a build that predates slicesByMode: the category ring
        // must fall back to categorySlices rather than render blank until the app
        // next runs.
        val legacy = WidgetSnapshot(
            ownerId = ownerA,
            periodTotals = mapOf(WidgetMode.CATEGORIES.name to "500"),
            categorySlices = listOf(WidgetCategorySlice("Food", "500"))
        )

        assertEquals(1, legacy.slices(WidgetMode.CATEGORIES).size)
        assertFalse(legacy.isEmpty(WidgetMode.CATEGORIES))
    }

    @Test
    fun `mode cycling visits every presentation and returns to the first`() {
        var mode = WidgetMode.DAY
        val visited = mutableListOf(mode)
        repeat(WidgetMode.entries.size) {
            mode = mode.next()
            visited += mode
        }
        assertEquals(WidgetMode.entries + WidgetMode.DAY, visited)
    }

    @Test
    fun `an unknown persisted mode falls back rather than throwing`() {
        assertEquals(WidgetMode.DAY, WidgetMode.fromName("TRENDS_REMOVED_IN_A_LATER_BUILD"))
        assertEquals(WidgetMode.DAY, WidgetMode.fromName(null))
        assertEquals(WidgetMode.MONTH, WidgetMode.fromName("MONTH"))
    }

    @Test
    fun `sign-out clears the stored snapshot rather than leaving stale totals`() {
        val store = InMemoryWidgetSnapshotStore()
        store.write(build(ownerA, listOf(expense(ownerA, "10.00"))))
        assertNotNull(store.read())

        store.clear()

        // Null, not zeros: a reader must be able to tell "signed out" from "this
        // account has spent nothing", and the stale owner id must not survive.
        assertNull("a cleared snapshot must not be readable", store.read())
    }
}
