package com.maghizhan.tabby.widget

import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.ui.home.CategoryTotal
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
    /**
     * Slices for EVERY mode, keyed by [WidgetMode.name].
     *
     * iOS draws a ring in every presentation except trends, each from its own
     * period's slices. With only the month's slices stored, TODAY and THIS WEEK
     * had nothing to draw a ring from and fell back to a bare number, which is
     * the most visible difference between the two widgets.
     *
     * Added alongside [categorySlices] rather than replacing it so a snapshot
     * written by an older build still deserializes instead of leaving the widget
     * blank until the app next runs.
     */
    val slicesByMode: Map<String, List<WidgetCategorySlice>> = emptyMap(),
    val generatedAtEpochMillis: Long = 0L
) {
    fun total(mode: WidgetMode): BigDecimal =
        periodTotals[mode.name]?.let { runCatching { BigDecimal(it) }.getOrNull() } ?: BigDecimal.ZERO

    /** The ring slices for [mode], falling back to the month's for old snapshots. */
    fun slices(mode: WidgetMode): List<WidgetCategorySlice> =
        slicesByMode[mode.name] ?: if (mode == WidgetMode.CATEGORIES) categorySlices else emptyList()

    val hasSpending: Boolean
        get() = periodTotals.values.any {
            (runCatching { BigDecimal(it) }.getOrNull() ?: BigDecimal.ZERO).signum() > 0
        }

    /**
     * True when the presentation [mode] has nothing to draw.
     *
     * Per mode rather than per account, matching the iOS widget: it renders
     * "No spending yet" from the SELECTED period's slices, so a quiet day shows
     * the empty state even when the month has spending. A global
     * "has this account ever spent" check instead drew a bare ₹0, which reads
     * as a broken widget rather than a quiet day.
     */
    fun isEmpty(mode: WidgetMode): Boolean = when (mode) {
        WidgetMode.CATEGORIES -> slices(mode).none { it.total.signum() > 0 }
        else -> total(mode).signum() <= 0
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

        // Every mode gets its OWN slices so the widget can draw a ring in each,
        // as iOS does. Computing only the month's left TODAY and THIS WEEK with
        // no ring at all.
        val slicesByMode = WidgetMode.entries.associate { mode ->
            val (start, end) = SpendingAnalytics.intervalOf(mode.period, now, zone, locale)
            mode.name to rankedSlices(
                SpendingAnalytics.totalsByCategory(SpendingAnalytics.inRange(own, start, end))
            )
        }

        return WidgetSnapshot(
            ownerId = owner,
            periodTotals = totals,
            categorySlices = rankedSlices(ranked),
            slicesByMode = slicesByMode,
            generatedAtEpochMillis = now.toEpochMilli()
        )
    }

    /**
     * The top categories, with the tail aggregated into "Other".
     *
     * Aggregated rather than dropped so the listed rows still sum to the
     * displayed period total — the same rule as the iOS ring.
     */
    private fun rankedSlices(ranked: List<CategoryTotal>): List<WidgetCategorySlice> =
        if (ranked.size <= WidgetSnapshot.MAXIMUM_SLICES) {
            ranked.map { WidgetCategorySlice(it.category, it.total.toPlainString()) }
        } else {
            val top = ranked.take(WidgetSnapshot.MAXIMUM_SLICES)
            val remainder = ranked.drop(WidgetSnapshot.MAXIMUM_SLICES)
                .fold(BigDecimal.ZERO) { sum, row -> sum.add(row.total) }
            top.map { WidgetCategorySlice(it.category, it.total.toPlainString()) } +
                WidgetCategorySlice("Other", remainder.toPlainString())
        }
}
