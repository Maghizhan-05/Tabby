package com.maghizhan.tabby.widget

import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.ui.home.PeriodUnit
import com.maghizhan.tabby.ui.home.SpendingAnalytics
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The presentations the widget can show, cycled by tapping its header.
 *
 * A subset of the iOS `AnalyticsWidgetMode` (daily / weekly / monthly /
 * categories): Android has no widget configuration intent equivalent, so the
 * mode is cycled in place and persisted per widget instance instead of being
 * chosen in a configuration sheet.
 */
enum class WidgetMode(val title: String) {
    DAY("TODAY"),
    WEEK("THIS WEEK"),
    MONTH("THIS MONTH"),
    CATEGORIES("BY CATEGORY");

    /** The next presentation, wrapping around. */
    fun next(): WidgetMode = entries[(ordinal + 1) % entries.size]

    /** The period a mode aggregates over; CATEGORIES breaks down the month. */
    val period: PeriodUnit
        get() = when (this) {
            DAY -> PeriodUnit.DAY
            WEEK -> PeriodUnit.WEEK
            MONTH, CATEGORIES -> PeriodUnit.MONTH
        }

    companion object {
        /** Parses a persisted name, falling back to [DAY] rather than throwing. */
        fun fromName(name: String?): WidgetMode =
            entries.firstOrNull { it.name == name } ?: DAY
    }
}

/** One category row in the widget's breakdown. */
@Serializable
data class WidgetCategorySlice(
    val category: String,
    /** Plain-string decimal; `BigDecimal` is not serializable and money never goes through a float. */
    val amount: String
) {
    val total: BigDecimal get() = runCatching { BigDecimal(amount) }.getOrDefault(BigDecimal.ZERO)
}

/**
 * Everything the widget renders, for ONE account.
 *
 * The widget reads only this snapshot — never the Room cache. The cache is
 * shared by every account that has ever signed in on the device and the widget
 * process has no session to scope a query with, so an unscoped read totalled
 * other accounts' rows and kept showing them after sign-out. A snapshot is
 * written by the app (which does have the active owner), is already
 * owner-filtered and tombstone-filtered, and is CLEARED on sign-out or account
 * change, which no amount of care inside a widget query could achieve.
 *
 * It is also deliberately sanitised: totals and category names only. No note,
 * no row id, no owner-identifying payload beyond the id used to detect staleness.
 */
@Serializable
data class WidgetSnapshot(
    val ownerId: String,
    /** Keyed by [WidgetMode.name] so adding a mode cannot break deserialization. */
    val periodTotals: Map<String, String> = emptyMap(),
    val categorySlices: List<WidgetCategorySlice> = emptyList(),
    val generatedAtEpochMillis: Long = 0L
) {
    fun total(mode: WidgetMode): BigDecimal =
        periodTotals[mode.name]?.let { runCatching { BigDecimal(it) }.getOrNull() } ?: BigDecimal.ZERO

    val hasSpending: Boolean
        get() = periodTotals.values.any {
            (runCatching { BigDecimal(it) }.getOrNull() ?: BigDecimal.ZERO).signum() > 0
        }

    companion object {
        /** Shown when signed out: zeros, never another account's numbers. */
        val SIGNED_OUT = WidgetSnapshot(ownerId = "")

        /** How many categories the breakdown lists before aggregating the rest. */
        const val MAXIMUM_SLICES = 4
    }
}

/**
 * Builds a [WidgetSnapshot] from an account's own expenses.
 *
 * Pure: takes the rows and the clock, so every rule here is unit-testable
 * without a database, a widget host, or a session. The caller is responsible for
 * passing [expenses] it read with an owner-scoped query; they are filtered again
 * here because a snapshot that leaks another account's spending onto the home
 * screen is exactly the defect this type exists to prevent.
 */
object WidgetSnapshotFactory {

    fun build(
        ownerId: String,
        expenses: List<ExpenseEntity>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): WidgetSnapshot {
        val owner = ownerId.trim()
        if (owner.isEmpty()) return WidgetSnapshot.SIGNED_OUT

        val own = expenses.filter { it.ownerId.equals(owner, ignoreCase = true) }

        val totals = WidgetMode.entries.associate { mode ->
            val (start, end) = SpendingAnalytics.intervalOf(mode.period, now, zone, locale)
            mode.name to SpendingAnalytics.total(SpendingAnalytics.inRange(own, start, end)).toPlainString()
        }

        val (monthStart, monthEnd) = SpendingAnalytics.intervalOf(PeriodUnit.MONTH, now, zone, locale)
        val ranked = SpendingAnalytics.totalsByCategory(
            SpendingAnalytics.inRange(own, monthStart, monthEnd)
        )

        // Everything past the first few categories is aggregated rather than
        // dropped, so the listed rows still sum to the displayed month total —
        // the same rule as the iOS ring.
        val slices = if (ranked.size <= WidgetSnapshot.MAXIMUM_SLICES) {
            ranked.map { WidgetCategorySlice(it.category, it.total.toPlainString()) }
        } else {
            val top = ranked.take(WidgetSnapshot.MAXIMUM_SLICES)
            val remainder = ranked.drop(WidgetSnapshot.MAXIMUM_SLICES)
                .fold(BigDecimal.ZERO) { sum, row -> sum.add(row.total) }
            top.map { WidgetCategorySlice(it.category, it.total.toPlainString()) } +
                WidgetCategorySlice("Other", remainder.toPlainString())
        }

        return WidgetSnapshot(
            ownerId = owner,
            periodTotals = totals,
            categorySlices = slices,
            generatedAtEpochMillis = now.toEpochMilli()
        )
    }
}
