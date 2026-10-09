package com.maghizhan.tabby.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
 * The Categories ring's ceiling.
 *
 * Sized against the legend beside it, not the card: the ring may grow until the
 * legend's longest "Category  ₹amount" line would start to wrap. Past this the
 * row stops reading as a chart WITH a key and starts clipping category names.
 */
private val CATEGORY_RING_MAX = 164.dp

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

        // Switching presentation cross-fades and lifts, the port of the iOS
        // `.transition(.opacity.combined(with: .scale(scale: 0.98)))`. Without
        // it the card's whole contents teleport on every pill tap, which is the
        // clearest "unfinished" tell in the app: the data is right, the change
        // just has no craft to it.
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                (
                    fadeIn(tween(220, delayMillis = 40)) +
                        scaleIn(initialScale = 0.98f, animationSpec = tween(260, delayMillis = 40))
                    ) togetherWith fadeOut(tween(140))
            },
            modifier = bodyModifier,
            label = "analyticsMode"
        ) { shown ->
        when (shown) {
            AnalyticsMode.DAILY -> PeriodDonutView(
                title = "Today",
                expenses = expenses,
                unit = PeriodUnit.DAY,
                selectedCategory = selectedCategory,
                onCategorySelected = onCategorySelected,
                now = now,
                zone = zone,
                fillHeight = fillHeight
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
                fillHeight = fillHeight
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
                fillHeight = fillHeight
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
                fillHeight = fillHeight
            )

            AnalyticsMode.CATEGORIES -> CategoryBreakdownView(
                expenses = expenses,
                selectedCategory = selectedCategory,
                onCategorySelected = onCategorySelected,
                fillHeight = fillHeight
            )

            AnalyticsMode.TRENDS -> TrendsView(
                expenses = expenses,
                now = now,
                zone = zone,
                fillHeight = fillHeight
            )
        }
        }
    }
}

@Composable
private fun ModeSelector(
    mode: AnalyticsMode,
    onModeSelected: (AnalyticsMode) -> Unit
) {
    val colors = Tabby.colors
    val scrollState = rememberScrollState()

    // Six modes cannot fit 288dp of usable width at a legible size, so the row
    // scrolls — it always did. What was wrong is that it gave no sign of it:
    // "Yearly" was sliced mid-word at the right edge and read as a layout bug
    // rather than as more content. A fade at each live edge is the iOS cue that
    // a rail continues, and it appears only on the side that actually has more,
    // so a fully-scrolled rail shows no false affordance.
    val fadeStart by animateFloatAsState(
        targetValue = if (scrollState.value > 0) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "modeFadeStart"
    )
    val fadeEnd by animateFloatAsState(
        targetValue = if (scrollState.value < scrollState.maxValue) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "modeFadeEnd"
    )

    // Keep the SELECTED pill on screen.
    //
    // The rail scrolls, so the active mode can sit outside the viewport — on a
    // 320dp screen the later modes start off-screen, and after a process death
    // or a return to Home the user saw a rail whose visible pills were all
    // unselected, with nothing indicating which mode the figures below actually
    // belong to. The fade says "there is more"; it cannot say "and your
    // selection is in it".
    val selectedIndex = AnalyticsMode.entries.indexOf(mode).coerceAtLeast(0)
    LaunchedEffect(mode, scrollState.maxValue) {
        if (scrollState.maxValue <= 0) return@LaunchedEffect
        // Proportional rather than measured: every pill carries the same
        // padding and the labels are within a few glyphs of each other, so
        // fraction-of-rail lands the selection comfortably inside the viewport
        // without plumbing per-item coordinates through for a six-item row.
        val fraction = selectedIndex.toFloat() / (AnalyticsMode.entries.size - 1).coerceAtLeast(1)
        scrollState.animateScrollTo((scrollState.maxValue * fraction).toInt())
    }

    Row(
        modifier = Modifier
            .background(colors.elevatedSurface.copy(alpha = 0.82f), CircleShape)
            .clip(CircleShape)
            // Drawn after the children so the fades sit ON the pills. BlendMode
            // .DstIn multiplies the alpha of what is already there, fading the
            // content to transparent rather than painting an opaque wash that
            // would have to match the card's own gradient to be invisible.
            .drawWithContent {
                drawContent()
                val fadeWidth = MODE_FADE_WIDTH.toPx()
                if (fadeStart > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = fadeStart)
                            ),
                            startX = 0f,
                            endX = fadeWidth
                        ),
                        size = androidx.compose.ui.geometry.Size(fadeWidth, size.height),
                        blendMode = BlendMode.DstIn
                    )
                }
                if (fadeEnd > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = fadeEnd),
                                Color.Transparent
                            ),
                            startX = size.width - fadeWidth,
                            endX = size.width
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(size.width - fadeWidth, 0f),
                        size = androidx.compose.ui.geometry.Size(fadeWidth, size.height),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
            .horizontalScroll(scrollState)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        AnalyticsMode.entries.forEach { candidate ->
            val selected = candidate == mode
            // The pill's fill and its label CROSS-FADE rather than switching on
            // the frame of the tap. A hard swap is the difference between a
            // control that feels built and one that feels like a toggle someone
            // wired up; iOS animates the same change with a spring.
            val fill by animateColorAsState(
                targetValue = if (selected) colors.accent else Color.Transparent,
                animationSpec = tween(durationMillis = 220),
                label = "modePillFill"
            )
            val labelColor by animateColorAsState(
                targetValue = if (selected) colors.paper else colors.subtleInk,
                animationSpec = tween(durationMillis = 220),
                label = "modePillLabel"
            )

            Text(
                text = candidate.label,
                color = labelColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .background(fill, CircleShape)
                    .clip(CircleShape)
                    .selectable(
                        selected = selected,
                        interactionSource = remember { MutableInteractionSource() },
                        // No ripple: a grey Material splash on a gold capsule is
                        // the most obviously un-iOS thing a tap can do here.
                        indication = null,
                        role = Role.Tab,
                        onClick = { onModeSelected(candidate) }
                    )
                    // 12dp, from 14dp: five pills' worth of saved width is most
                    // of a sixth label, so materially more of the rail is
                    // readable without shrinking the type.
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/** How far the scroll-affordance fade reaches in from each live edge. */
private val MODE_FADE_WIDTH = 20.dp

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
    fillHeight: Boolean = false
) {
    val (start, end) = SpendingAnalytics.intervalOf(unit, now, zone)
    val inPeriod = SpendingAnalytics.inRange(expenses, start, end)
    val totals = SpendingAnalytics.totalsByCategory(inPeriod)
    val filling = fillHeight

    Column(
        modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AmountHeadline(title = title, amount = SpendingAnalytics.total(inPeriod))
        if (totals.isEmpty()) {
            EmptyAnalytics()
        } else {
            // Boxed and centred rather than `fillMaxWidth`: the donut draws
            // into the SMALLER of its two dimensions, so a full-width, short
            // canvas produced a ring sized by the leftover height with wide
            // dead margins either side of it. The box takes the width, the ring
            // takes the height, and the result is centred and as large as the
            // card allows.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .chartHeight(this, filling),
                contentAlignment = Alignment.Center
            ) {
                CategoryDonut(
                    totals = totals,
                    selectedCategory = selectedCategory,
                    innerRadiusRatio = 0.62f,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                )
            }
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
    fillHeight: Boolean = false
) {
    val filling = fillHeight
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
        modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier,
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
    fillHeight: Boolean = false
) {
    val filling = fillHeight
    val totals = SpendingAnalytics.totalsByCategory(expenses)

    Column(
        modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier,
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
                        .sizeIn(maxWidth = CATEGORY_RING_MAX, maxHeight = CATEGORY_RING_MAX)
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
    fillHeight: Boolean = false
) {
    val filling = fillHeight
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
        modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier,
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
