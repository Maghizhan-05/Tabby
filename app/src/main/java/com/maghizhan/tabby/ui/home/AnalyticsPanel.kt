package com.maghizhan.tabby.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.ui.common.AmountHeadline
import com.maghizhan.tabby.ui.common.EmptyAnalytics
import com.maghizhan.tabby.ui.theme.Tabby
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/**
 * The chart height used when the panel is NOT given a height to fill — previews,
 * tests, and any caller that lets the card size itself.
 */
private val DEFAULT_CHART_HEIGHT = 160.dp

/**
 * Give the chart the column's leftover height when the card is height-capped,
 * or [DEFAULT_CHART_HEIGHT] when it is free to size itself.
 *
 * Takes the [ColumnScope] explicitly because `Modifier.weight` is only defined
 * inside the scope that measures it, and the call site is one `Column` deeper
 * than the receiver this extension is written against.
 */
private fun Modifier.chartHeight(scope: ColumnScope, filling: Boolean): Modifier =
    if (filling) with(scope) { weight(1f) } else height(DEFAULT_CHART_HEIGHT)

/**
 * The analytics canvas: one visual at a time behind a compact mode selector.
 *
 * `now` and `zone` are parameters rather than reads of the system clock inside
 * the composable so the panel renders deterministically in tests and previews —
 * a chart that depends on a hidden `Instant.now()` cannot be asserted on.
 */
@Composable
fun AnalyticsPanel(
    expenses: List<ExpenseEntity>,
    mode: AnalyticsMode,
    selectedCategory: String?,
    onModeSelected: (AnalyticsMode) -> Unit,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    fillHeight: Boolean = false
) {
    // `fillHeight` is threaded down to each presentation rather than resolved
    // into a modifier here: `Modifier.weight` only exists inside the ColumnScope
    // that will actually measure it, and each presentation owns its own Column.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ModeSelector(mode = mode, onModeSelected = onModeSelected)

        // The presentation gets every remaining pixel of a capped card, and the
        // chart inside it then takes what its own headline and legend leave.
        // Weight rather than a dp budget: a guessed budget clipped the ring on
        // one screen and left dead space under it on another.
        val bodyModifier = if (fillHeight) Modifier.weight(1f) else Modifier

        when (mode) {
            AnalyticsMode.DAILY -> PeriodDonutView(
                title = "Today",
                expenses = expenses,
                unit = PeriodUnit.DAY,
                selectedCategory = selectedCategory,
                onCategorySelected = onCategorySelected,
                now = now,
                zone = zone,
                modifier = bodyModifier
            )

            AnalyticsMode.WEEKLY -> PeriodBarView(
                title = "This Week",
                expenses = expenses,
                windowUnit = PeriodUnit.WEEK,
                bucketUnit = PeriodUnit.DAY,
                buckets = 7,
                labelPattern = "EEE",
                now = now,
                zone = zone,
                modifier = bodyModifier
            )

            AnalyticsMode.MONTHLY -> PeriodBarView(
                title = "This Month",
                expenses = expenses,
                windowUnit = PeriodUnit.MONTH,
                bucketUnit = PeriodUnit.WEEK,
                buckets = 5,
                labelPattern = "'W'w",
                now = now,
                zone = zone,
                modifier = bodyModifier
            )

            AnalyticsMode.YEARLY -> PeriodBarView(
                title = "This Year",
                expenses = expenses,
                windowUnit = PeriodUnit.YEAR,
                bucketUnit = PeriodUnit.MONTH,
                buckets = 12,
                // Single initial, not "MMM": twelve 3-letter labels across a
                // phone width get clipped to "De"/"Ma", which is worse than an
                // unambiguous-in-order initial. Seen on device.
                labelPattern = "MMMMM",
                now = now,
                zone = zone,
                modifier = bodyModifier
            )

            AnalyticsMode.CATEGORIES -> CategoryBreakdownView(
                expenses = expenses,
                selectedCategory = selectedCategory,
                onCategorySelected = onCategorySelected,
                modifier = bodyModifier
            )

            AnalyticsMode.TRENDS -> TrendsView(
                expenses = expenses,
                now = now,
                zone = zone,
                modifier = bodyModifier
            )
        }
    }
}

@Composable
private fun ModeSelector(
    mode: AnalyticsMode,
    onModeSelected: (AnalyticsMode) -> Unit
) {
    val colors = Tabby.colors
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .background(colors.elevatedSurface.copy(alpha = 0.82f), CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        AnalyticsMode.entries.forEach { candidate ->
            val selected = candidate == mode
            Text(
                text = candidate.label,
                color = if (selected) colors.paper else colors.subtleInk,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(if (selected) colors.accent else Color.Transparent, CircleShape)
                    .clickable { onModeSelected(candidate) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/** Daily: the current period's total plus a category ring and legend. */
@Composable
private fun PeriodDonutView(
    title: String,
    expenses: List<ExpenseEntity>,
    unit: PeriodUnit,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
    now: Instant,
    zone: ZoneId,
    modifier: Modifier = Modifier
) {
    val (start, end) = SpendingAnalytics.intervalOf(unit, now, zone)
    val inPeriod = SpendingAnalytics.inRange(expenses, start, end)
    val totals = SpendingAnalytics.totalsByCategory(inPeriod)
    val filling = modifier != Modifier

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AmountHeadline(title = title, amount = SpendingAnalytics.total(inPeriod))
        if (totals.isEmpty()) {
            EmptyAnalytics()
        } else {
            CategoryDonut(
                totals = totals,
                selectedCategory = selectedCategory,
                innerRadiusRatio = 0.62f,
                modifier = Modifier
                    .fillMaxWidth()
                    .chartHeight(this, filling)
            )
            SelectedSliceCallout(totals, selectedCategory)
            CategoryLegend(
                totals = totals,
                selectedCategory = selectedCategory,
                onSelect = onCategorySelected,
                showAmounts = false,
                // Two columns under the full-width ring, as on iOS. One column
                // left the right half of the card empty.
                columns = 2
            )
        }
    }
}

/** Weekly / Monthly / Yearly: a window total plus bucketed bars. */
@Composable
private fun PeriodBarView(
    title: String,
    expenses: List<ExpenseEntity>,
    windowUnit: PeriodUnit,
    bucketUnit: PeriodUnit,
    buckets: Int,
    labelPattern: String,
    now: Instant,
    zone: ZoneId,
    modifier: Modifier = Modifier
) {
    val filling = modifier != Modifier
    val (start, end) = SpendingAnalytics.intervalOf(windowUnit, now, zone)
    val windowTotal = SpendingAnalytics.total(SpendingAnalytics.inRange(expenses, start, end))
    val byBucket = SpendingAnalytics.totalsByPeriod(
        expenses = expenses,
        unit = bucketUnit,
        count = buckets,
        labelPattern = labelPattern,
        now = now,
        zone = zone
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AmountHeadline(title = title, amount = windowTotal)
        if (windowTotal.signum() == 0) {
            EmptyAnalytics()
        } else {
            PeriodBars(
                totals = byBucket,
                modifier = Modifier
                    .fillMaxWidth()
                    .chartHeight(this, filling)
            )
        }
    }
}

/** All-time category breakdown: ring beside a legend with amounts. */
@Composable
private fun CategoryBreakdownView(
    expenses: List<ExpenseEntity>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val filling = modifier != Modifier
    val totals = SpendingAnalytics.totalsByCategory(expenses)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AmountHeadline(title = "By Category", amount = SpendingAnalytics.total(expenses))
        if (totals.isEmpty()) {
            EmptyAnalytics()
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .chartHeight(this@Column, filling),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Smaller than it looks in a preview: at 130dp the legend beside
                // it was squeezed and clipped "Transport" to "Tran". 120dp is
                // as close to the iOS 130pt ring as the legend's full-currency
                // amounts leave room for.
                CategoryDonut(
                    totals = totals,
                    selectedCategory = selectedCategory,
                    innerRadiusRatio = 0.60f,
                    // Square, never over the iOS 130pt ring, and smaller still
                    // if the card is short. `sizeIn` comes FIRST: constraints
                    // flow left to right, so a `fillMaxHeight` ahead of it had
                    // already claimed the row's full height and the cap did
                    // nothing — the ring filled half the card on device.
                    modifier = Modifier
                        .sizeIn(maxWidth = 120.dp, maxHeight = 120.dp)
                        .fillMaxHeight()
                        .aspectRatio(1f)
                )
                CategoryLegend(
                    totals = totals,
                    selectedCategory = selectedCategory,
                    onSelect = onCategorySelected,
                    showAmounts = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Trends: the last 30 days as a line with its filled area. */
@Composable
private fun TrendsView(
    expenses: List<ExpenseEntity>,
    now: Instant,
    zone: ZoneId,
    modifier: Modifier = Modifier
) {
    val filling = modifier != Modifier
    val last30 = SpendingAnalytics.totalsByPeriod(
        expenses = expenses,
        unit = PeriodUnit.DAY,
        count = 30,
        labelPattern = "M/d",
        now = now,
        zone = zone
    )
    val trendTotal = last30.fold(BigDecimal.ZERO) { sum, point -> sum.add(point.total) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AmountHeadline(title = "Last 30 Days", amount = trendTotal)
        if (trendTotal.signum() == 0) {
            EmptyAnalytics()
        } else {
            TrendLine(
                totals = last30,
                modifier = Modifier
                    .fillMaxWidth()
                    .chartHeight(this, filling)
            )
        }
    }
}

@Composable
private fun SelectedSliceCallout(
    totals: List<CategoryTotal>,
    selectedCategory: String?
) {
    val index = totals.indexOfFirst { it.category == selectedCategory }
    if (index < 0) return
    Box(modifier = Modifier.fillMaxWidth()) {
        SelectionCallout(
            item = totals[index],
            total = totals.fold(BigDecimal.ZERO) { sum, item -> sum.add(item.total) },
            index = index
        )
    }
}
