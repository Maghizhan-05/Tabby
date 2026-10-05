package com.maghizhan.tabby.ui.home

import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID

/**
 * Analytics bucketing and totals.
 *
 * Every case pins an explicit instant and zone: an assertion that depends on
 * the wall clock passes in the morning and fails at midnight.
 */
class SpendingAnalyticsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    /** 2026-03-15 is a Sunday; 14:30 local. */
    private val now: Instant =
        ZonedDateTime.of(2026, 3, 15, 14, 30, 0, 0, zone).toInstant()

    private fun expense(
        amount: String,
        category: String = "Food",
        at: Instant = now,
        state: SyncState = SyncState.SYNCED
    ) = ExpenseEntity(
        id = UUID.randomUUID(),
        amount = BigDecimal(amount),
        categoryName = category,
        note = null,
        date = at,
        createdAt = at,
        updatedAt = at,
        syncStateRaw = state.raw,
        remoteId = null,
        ownerId = "owner-a"
    )

    /**
     * Numeric equality for money.
     *
     * `assertEquals` on BigDecimal compares scale too, and `stripTrailingZeros`
     * turns 10 into 1E+1 — both make a correct total look like a failure. What
     * matters here is the value.
     */
    private fun assertAmount(expected: String, actual: BigDecimal) {
        assertEquals(
            "expected $expected but was ${actual.toPlainString()}",
            0,
            BigDecimal(expected).compareTo(actual)
        )
    }

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0
    ): Instant = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant()

    @Test
    fun `total sums every amount exactly`() {
        val total = SpendingAnalytics.total(
            listOf(expense("10.01"), expense("0.02"), expense("1999.97"))
        )
        // Exact decimal equality: a float accumulation would land on 2010.000000001
        assertAmount("2010.00", total)
    }

    @Test
    fun `total of nothing is zero not null`() {
        assertEquals(0, SpendingAnalytics.total(emptyList()).signum())
    }

    @Test
    fun `totalsByCategory groups case-insensitively under one label`() {
        val totals = SpendingAnalytics.totalsByCategory(
            listOf(
                expense("10", category = "Food"),
                expense("5", category = "food"),
                expense("20", category = "Transport")
            )
        )
        assertEquals(2, totals.size)
        val food = totals.first { it.category.equals("Food", ignoreCase = true) }
        assertAmount("15", food.total)
    }

    @Test
    fun `totalsByCategory sorts by amount descending so the ring reads largest first`() {
        val totals = SpendingAnalytics.totalsByCategory(
            listOf(
                expense("10", category = "Food"),
                expense("99", category = "Bills"),
                expense("50", category = "Transport")
            )
        )
        assertEquals(listOf("Bills", "Transport", "Food"), totals.map { it.category })
    }

    @Test
    fun `totalsByCategory excludes tombstoned rows`() {
        val totals = SpendingAnalytics.totalsByCategory(
            listOf(
                expense("10", category = "Food"),
                expense("999", category = "Food", state = SyncState.DELETED)
            )
        )
        assertAmount("10", totals.single().total)
    }

    @Test
    fun `inRange is half-open so a boundary row is counted exactly once`() {
        val (start, end) = SpendingAnalytics.intervalOf(PeriodUnit.DAY, now, zone)
        val atStart = expense("1", at = start)
        val atEnd = expense("2", at = end)

        val today = SpendingAnalytics.inRange(listOf(atStart, atEnd), start, end)
        assertEquals(listOf(atStart), today)

        // The row at `end` belongs to the NEXT day's interval, not to neither.
        val (nextStart, nextEnd) = SpendingAnalytics.intervalOf(
            PeriodUnit.DAY,
            end,
            zone
        )
        assertEquals(listOf(atEnd), SpendingAnalytics.inRange(listOf(atStart, atEnd), nextStart, nextEnd))
    }

    @Test
    fun `day interval starts at local midnight not UTC midnight`() {
        val (start, _) = SpendingAnalytics.intervalOf(PeriodUnit.DAY, now, zone)
        val local = start.atZone(zone)
        assertEquals(0, local.hour)
        assertEquals(0, local.minute)
        assertEquals(15, local.dayOfMonth)
    }

    @Test
    fun `week interval honours the locale first day of week`() {
        // 2026-03-15 is a Sunday. en-US weeks start Sunday, so the interval
        // starts that day; fr-FR weeks start Monday, so it starts the 9th.
        val (usStart, _) = SpendingAnalytics.intervalOf(PeriodUnit.WEEK, now, zone, Locale.US)
        val (frStart, _) = SpendingAnalytics.intervalOf(PeriodUnit.WEEK, now, zone, Locale.FRANCE)

        assertEquals(15, usStart.atZone(zone).dayOfMonth)
        assertEquals(9, frStart.atZone(zone).dayOfMonth)
    }

    @Test
    fun `month interval covers the first to the last day inclusive`() {
        val (start, end) = SpendingAnalytics.intervalOf(PeriodUnit.MONTH, now, zone)
        assertEquals(1, start.atZone(zone).dayOfMonth)
        assertEquals(3, start.atZone(zone).monthValue)
        // End is exclusive: 1 April, so 31 March 23:59 is still inside.
        assertEquals(1, end.atZone(zone).dayOfMonth)
        assertEquals(4, end.atZone(zone).monthValue)
        assertTrue(
            SpendingAnalytics.inRange(
                listOf(expense("1", at = at(2026, 3, 31, 23, 59))),
                start,
                end
            ).isNotEmpty()
        )
    }

    @Test
    fun `year interval spans January to December`() {
        val (start, end) = SpendingAnalytics.intervalOf(PeriodUnit.YEAR, now, zone)
        assertEquals(1, start.atZone(zone).monthValue)
        assertEquals(2026, start.atZone(zone).year)
        assertEquals(2027, end.atZone(zone).year)
    }

    @Test
    fun `totalsByPeriod returns one bucket per requested slot even when empty`() {
        val buckets = SpendingAnalytics.totalsByPeriod(
            expenses = listOf(expense("10", at = now)),
            unit = PeriodUnit.DAY,
            count = 7,
            labelPattern = "EEE",
            now = now,
            zone = zone
        )
        // Seven slots, not one: a bar chart with gaps collapsed would misstate
        // the shape of the week.
        assertEquals(7, buckets.size)
        assertEquals(6, buckets.count { it.total.signum() == 0 })
    }

    @Test
    fun `totalsByPeriod is chronological with the current slot last`() {
        val buckets = SpendingAnalytics.totalsByPeriod(
            expenses = emptyList(),
            unit = PeriodUnit.DAY,
            count = 3,
            labelPattern = "d",
            now = now,
            zone = zone
        )
        assertEquals(listOf("13", "14", "15"), buckets.map { it.label })
    }

    @Test
    fun `totalsByPeriod places each expense in its own day bucket`() {
        val buckets = SpendingAnalytics.totalsByPeriod(
            expenses = listOf(
                expense("10", at = at(2026, 3, 13)),
                expense("20", at = at(2026, 3, 15)),
                expense("5", at = at(2026, 3, 15, 23, 59))
            ),
            unit = PeriodUnit.DAY,
            count = 3,
            labelPattern = "d",
            now = now,
            zone = zone
        )
        assertAmount("10", buckets[0].total)
        assertEquals(0, buckets[1].total.signum())
        assertAmount("25", buckets[2].total)
    }

    @Test
    fun `totalsByPeriod ignores rows older than the window`() {
        val buckets = SpendingAnalytics.totalsByPeriod(
            expenses = listOf(expense("9999", at = at(2025, 1, 1))),
            unit = PeriodUnit.DAY,
            count = 3,
            labelPattern = "d",
            now = now,
            zone = zone
        )
        assertTrue(buckets.all { it.total.signum() == 0 })
    }

    @Test
    fun `totalsByPeriod excludes tombstoned rows`() {
        val buckets = SpendingAnalytics.totalsByPeriod(
            expenses = listOf(expense("500", at = now, state = SyncState.DELETED)),
            unit = PeriodUnit.DAY,
            count = 1,
            labelPattern = "d",
            now = now,
            zone = zone
        )
        assertEquals(0, buckets.single().total.signum())
    }

    @Test
    fun `month buckets roll back across a year boundary`() {
        val january = at(2026, 1, 10)
        val buckets = SpendingAnalytics.totalsByPeriod(
            expenses = listOf(expense("42", at = at(2025, 12, 5))),
            unit = PeriodUnit.MONTH,
            count = 3,
            labelPattern = "MMM",
            now = january,
            zone = zone
        )
        assertEquals(3, buckets.size)
        // Nov 2025, Dec 2025, Jan 2026 — the December row must land, which it
        // cannot if the bucket key ignores the year.
        assertAmount("42", buckets[1].total)
    }
}
