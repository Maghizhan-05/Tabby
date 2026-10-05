package com.maghizhan.tabby.ui.home

import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.sync.SyncState
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** A category aggregate, ready for the ring/legend to render. */
data class CategoryTotal(
    val category: String,
    val total: BigDecimal,
    /** Index into `TabbyPalette.ringColors`; kept as an index so this stays UI-free. */
    val colorIndex: Int
)

/** A time-bucketed aggregate for bar and line charts. */
data class PeriodTotal(
    val label: String,
    val start: Instant,
    val total: BigDecimal
)

/** The period a chart buckets by, mirroring the iOS `Calendar.Component` usage. */
enum class PeriodUnit { DAY, WEEK, MONTH, YEAR }

/**
 * Pure aggregation over a list of expenses — the Android port of
 * `SpendingAnalytics.swift`.
 *
 * Deliberately free of Compose and Room imports (the same convention as
 * `Ownership.kt` and `MoneyValidation.kt`): every rule here is arithmetic over
 * dates and money, which is exactly the kind of logic that must be unit-tested
 * without a device. Colours are returned as indices rather than `Color` values
 * so this file never depends on the theme.
 *
 * Money stays `BigDecimal` end to end. Summing through `Double` would reintroduce
 * the float rounding the storage layer goes out of its way to avoid, and a
 * total that disagrees with the sum of its rows by a paisa is a bug report.
 */
object SpendingAnalytics {

    /**
     * Category totals, largest first.
     *
     * Ties are broken by category name so the ordering — and therefore the
     * ring colours — is stable across recompositions. The iOS version relies on
     * dictionary ordering here, which is why two equal categories could swap
     * colours between launches; this fixes that without changing the palette.
     */
    fun totalsByCategory(expenses: List<ExpenseEntity>): List<CategoryTotal> {
        // Keyed case-insensitively, displaying the first spelling seen.
        //
        // Local writes are normalised by EntryForm.canonicalCategoryName, but a
        // row PULLED from another device carries whatever casing that device
        // stored, so "Food" and "food" can both exist. Grouping by the raw name
        // would split one category across two ring slices with two colours.
        val buckets = LinkedHashMap<String, BigDecimal>()
        val labels = LinkedHashMap<String, String>()
        for (expense in expenses.filter { it.syncState != SyncState.DELETED }) {
            val key = expense.categoryName.trim().lowercase()
            labels.putIfAbsent(key, expense.categoryName.trim())
            buckets[key] = (buckets[key] ?: BigDecimal.ZERO).add(expense.amount)
        }
        return buckets.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, BigDecimal>> { it.value }
                    .thenBy { it.key.lowercase() }
            )
            .mapIndexed { index, entry ->
                CategoryTotal(
                    category = labels[entry.key] ?: entry.key,
                    total = entry.value,
                    colorIndex = index
                )
            }
    }

    /**
     * Sum of the amounts, excluding tombstones.
     *
     * Tombstones are already filtered in SQL by the owner-scoped DAO queries,
     * but excluded again here: this object is also fed by the widget's
     * unscoped read, and a total that silently includes deleted spends is the
     * kind of bug nobody notices until the numbers are wrong.
     */
    fun total(expenses: List<ExpenseEntity>): BigDecimal =
        expenses
            .filter { it.syncState != SyncState.DELETED }
            .fold(BigDecimal.ZERO) { sum, expense -> sum.add(expense.amount) }

    /** Expenses whose date falls in `[start, endExclusive)`. */
    fun inRange(
        expenses: List<ExpenseEntity>,
        start: Instant,
        endExclusive: Instant
    ): List<ExpenseEntity> = expenses.filter { it.date >= start && it.date < endExclusive }

    /** The half-open interval covering [unit] containing [instant], in [zone]. */
    fun intervalOf(
        unit: PeriodUnit,
        instant: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): Pair<Instant, Instant> {
        val local = instant.atZone(zone)
        return when (unit) {
            PeriodUnit.DAY -> {
                val start = local.truncatedTo(ChronoUnit.DAYS)
                start.toInstant() to start.plusDays(1).toInstant()
            }
            PeriodUnit.WEEK -> {
                // The locale's first day of week, not a hardcoded Monday: the
                // iOS version uses Calendar.current, so a user whose week
                // starts on Sunday must see the same seven days on both apps.
                val firstDay = WeekFields.of(locale).firstDayOfWeek
                val start = local.truncatedTo(ChronoUnit.DAYS)
                    .with(TemporalAdjusters.previousOrSame(firstDay))
                start.toInstant() to start.plusWeeks(1).toInstant()
            }
            PeriodUnit.MONTH -> {
                val start = local.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1)
                start.toInstant() to start.plusMonths(1).toInstant()
            }
            PeriodUnit.YEAR -> {
                val start = local.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1)
                start.toInstant() to start.plusYears(1).toInstant()
            }
        }
    }

    /**
     * Ordered totals for the last [count] buckets of [unit], oldest first and
     * ending with the bucket containing [now].
     *
     * Buckets are built by stepping whole calendar units backwards from `now`
     * and then taking each one's own interval, so a DST transition or a short
     * month shifts the boundary rather than the bucket count — subtracting a
     * fixed number of hours would silently merge or drop a day.
     */
    fun totalsByPeriod(
        expenses: List<ExpenseEntity>,
        unit: PeriodUnit,
        count: Int,
        labelPattern: String,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): List<PeriodTotal> {
        if (count <= 0) return emptyList()
        val formatter = DateTimeFormatter.ofPattern(labelPattern, locale).withZone(zone)
        val anchor = now.atZone(zone)

        return (count - 1 downTo 0).map { offset ->
            val bucketInstant = when (unit) {
                PeriodUnit.DAY -> anchor.minusDays(offset.toLong())
                PeriodUnit.WEEK -> anchor.minusWeeks(offset.toLong())
                PeriodUnit.MONTH -> anchor.minusMonths(offset.toLong())
                PeriodUnit.YEAR -> anchor.minusYears(offset.toLong())
            }.toInstant()

            val (start, end) = intervalOf(unit, bucketInstant, zone, locale)
            PeriodTotal(
                label = formatter.format(start),
                start = start,
                total = total(inRange(expenses, start, end))
            )
        }
    }
}
