package com.maghizhan.tabby.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.ui.common.LegendRow
import com.maghizhan.tabby.ui.format.CurrencyFormat
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyPalette
import com.maghizhan.tabby.ui.theme.moneyStyle
import java.math.BigDecimal
import java.math.RoundingMode

/** The ring colour for a slice, wrapping the seven-colour palette. */
fun ringColor(index: Int): Color =
    TabbyPalette.ringColors[index % TabbyPalette.ringColors.size]

/**
 * A donut ring of category totals, with an optional selected slice.
 *
 * Compose has no Swift-Charts equivalent, so the ring is drawn directly. Two
 * details that matter for correctness rather than looks:
 *
 * - The sweep of each slice is computed from `BigDecimal` proportions and only
 *   converted to a float at the final `drawArc`, so a rounding error cannot
 *   accumulate across slices and leave a visible gap.
 * - Every slice carries its own content description, because a canvas is opaque
 *   to screen readers: without them the whole chart is one unlabelled box.
 */
@Composable
fun CategoryDonut(
    totals: List<CategoryTotal>,
    selectedCategory: String?,
    innerRadiusRatio: Float,
    modifier: Modifier = Modifier
) {
    val total = totals.fold(BigDecimal.ZERO) { sum, item -> sum.add(item.total) }
    if (total.signum() <= 0) return

    val description = totals.joinToString(separator = ", ") { item ->
        "${item.category} ${CurrencyFormat.compact(item.total)}"
    }
    // Read here, outside the Canvas: a DrawScope is not a composable, so the
    // theme cannot be reached from inside the lambda.
    val trackColor = Tabby.colors.hairline.copy(alpha = 0.55f)

    Canvas(
        modifier = modifier.semantics {
            contentDescription = "Spending by category: $description"
        }
    ) {
        // A SLENDER ring, not a fat one. The stroke used to be derived from the
        // inner-radius ratio, which on a 160dp chart gave a ~30dp band that read
        // as a pie with a hole punched in it. iOS's ring is a thin, precise
        // band; capping the stroke is what makes it look drawn rather than
        // blobbed, and it is the single biggest "premium" tell on this screen.
        val outerDiameter = size.minDimension
        val stroke = (outerDiameter / 2f * (1f - innerRadiusRatio))
            .coerceAtMost(outerDiameter * MAXIMUM_STROKE_FRACTION)
        val diameter = outerDiameter - stroke
        val topLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f
        )

        // The unfilled track behind the slices. Without it a ring with one small
        // category is a lonely arc floating in space; with it, the arc reads as
        // progress around a complete circle — the iOS `TabbyOrbit` idea applied
        // to the chart.
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = Size(diameter, diameter),
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )

        // A single slice would otherwise be drawn as an arc with a gap and two
        // rounded ends, which reads as a ring that failed to close. At 100%
        // there is no neighbour to inset away from, so it is drawn whole.
        val single = totals.count { it.total.signum() > 0 } == 1

        var startAngle = -90f
        totals.forEachIndexed { index, item ->
            val fraction = item.total
                .divide(total, 8, RoundingMode.HALF_UP)
                .toFloat()
            val sweep = fraction * 360f
            val dimmed = selectedCategory != null && selectedCategory != item.category
            val color = ringColor(index).copy(alpha = if (dimmed) DIMMED_ALPHA else 1f)

            if (single) {
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
            } else {
                // Rounded caps with an angular inset, matching the iOS chart's
                // `cornerRadius(3)` + `angularInset(1.5)`. The inset is taken in
                // DEGREES scaled to the ring's radius so the visual gap stays
                // constant whether the ring is the big daily one or the small
                // breakdown one — a fixed degree inset looks twice as wide on
                // the small ring.
                //
                // Half the stroke, not the whole: a full-stroke inset on a thin
                // ring opened gaps wider than the slices between them.
                val insetDegrees =
                    (stroke / 2f) / (diameter / 2f) * (180f / Math.PI.toFloat()) * 0.5f
                val drawn = (sweep - insetDegrees * 2f).coerceAtLeast(MINIMUM_SWEEP_DEGREES)
                drawArc(
                    color = color,
                    startAngle = startAngle + (sweep - drawn) / 2f,
                    sweepAngle = drawn,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            startAngle += sweep
        }
    }
}

/**
 * The ring's band as a fraction of its outer diameter.
 *
 * Caps the stroke the inner-radius ratio would otherwise produce. iOS's ring is
 * a thin band; deriving the stroke purely from the ratio made it grow with the
 * chart until a 160dp daily ring was a pie with a hole in it.
 */
private const val MAXIMUM_STROKE_FRACTION = 0.13f

/** An unselected slice keeps enough presence to stay legible, not a ghost. */
private const val DIMMED_ALPHA = 0.28f

/** A tiny category still earns a visible tick rather than vanishing. */
private const val MINIMUM_SWEEP_DEGREES = 0.6f

/**
 * A bar chart of period totals.
 *
 * Bars are scaled against the largest bucket, with a floor so a non-zero but
 * tiny bucket still renders a visible sliver instead of disappearing — a bar of
 * height zero reads as "no spending", which would be wrong.
 */
@Composable
fun PeriodBars(
    totals: List<PeriodTotal>,
    modifier: Modifier = Modifier
) {
    val colors = Tabby.colors
    val maximum = totals.maxOfOrNull { it.total } ?: BigDecimal.ZERO
    val description = totals.joinToString(separator = ", ") { point ->
        "${point.label} ${CurrencyFormat.compact(point.total)}"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Spending by period: $description" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        totals.forEach { point ->
            val fraction = if (maximum.signum() <= 0) {
                0f
            } else {
                point.total.divide(maximum, 6, RoundingMode.HALF_UP).toFloat()
            }
            val isPeak = point.total.signum() > 0 && point.total.compareTo(maximum) == 0

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                // Each bar sits in a full-height track, so the chart reads as a
                // measured grid rather than a few gold blocks floating on the
                // card. The empty part of a short bar is still part of the
                // picture — it is how much of the peak that day did NOT reach.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(BAR_WIDTH_FRACTION)
                        .clip(BAR_SHAPE)
                        // `copy(alpha = …)` REPLACES the token's own alpha, so
                        // taking the hairline at 0.4 produced a 40%-white slab:
                        // every empty day read as a full-height grey bar and
                        // the chart looked like it had data it did not have.
                        // The track must be barely there.
                        .background(colors.ink.copy(alpha = BAR_TRACK_ALPHA)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(
                                if (point.total.signum() > 0) {
                                    fraction.coerceAtLeast(MINIMUM_BAR_FRACTION)
                                } else {
                                    0f
                                }
                            )
                            .clip(BAR_SHAPE)
                            // A vertical gradient, brightest at the top: a flat
                            // fill made every bar a solid gold slab. The peak
                            // bar keeps the full accent so the eye lands on the
                            // biggest period first, as the iOS chart does.
                            .background(
                                Brush.verticalGradient(
                                    if (isPeak) {
                                        listOf(colors.accentBright, colors.accent)
                                    } else {
                                        listOf(
                                            colors.accent.copy(alpha = 0.92f),
                                            colors.accent.copy(alpha = 0.55f)
                                        )
                                    }
                                )
                            )
                    )
                }
                Text(
                    text = point.label,
                    color = if (isPeak) colors.ink else colors.subtleInk,
                    fontSize = 10.sp,
                    fontWeight = if (isPeak) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** The unfilled track: present enough to measure against, never mistaken for data. */
private const val BAR_TRACK_ALPHA = 0.07f

/** Bars are narrower than their slot, so the chart breathes. */
private const val BAR_WIDTH_FRACTION = 0.62f

/** A non-zero but tiny bucket still renders a visible sliver. */
private const val MINIMUM_BAR_FRACTION = 0.02f

/** Rounded on all four corners; a square-topped bar looks unfinished. */
private val BAR_SHAPE = RoundedCornerShape(5.dp)

/**
 * A 30-day trend line with a filled area beneath it.
 *
 * Straight segments rather than the iOS catmull-rom interpolation: a smoothed
 * curve can dip below zero between two low points, which would draw spending
 * that never happened.
 */
@Composable
fun TrendLine(
    totals: List<PeriodTotal>,
    modifier: Modifier = Modifier
) {
    val colors = Tabby.colors
    val maximum = totals.maxOfOrNull { it.total } ?: BigDecimal.ZERO
    if (totals.size < 2) return

    val description = "Last ${totals.size} days, " +
        "peak ${CurrencyFormat.compact(maximum)}"

    Canvas(
        modifier = modifier.semantics { contentDescription = description }
    ) {
        // Inset so the line's stroke and the peak marker are not half-clipped by
        // the canvas edge, which made the chart look cropped rather than drawn.
        // Inset on BOTH axes. Horizontally too, because the first and last
        // points sat exactly on the canvas edge: the marker on a peak at either
        // end was sliced in half by the card, and the line ran flush into the
        // card's rounded corner instead of ending inside the plot.
        val inset = TREND_MARKER_RADIUS.toPx() * 1.9f + 1.dp.toPx()
        val plotHeight = size.height - inset * 2f
        val plotWidth = size.width - inset * 2f

        val points = totals.mapIndexed { index, point ->
            val fraction = if (maximum.signum() <= 0) {
                0f
            } else {
                point.total.divide(maximum, 6, RoundingMode.HALF_UP).toFloat()
            }
            Offset(
                x = inset + plotWidth * index / (totals.size - 1).toFloat(),
                y = inset + plotHeight * (1f - fraction)
            )
        }

        val area = Path().apply {
            moveTo(points.first().x, size.height)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, size.height)
            close()
        }
        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                listOf(colors.accent.copy(alpha = 0.28f), Color.Transparent)
            )
        )

        val line = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        // A soft wide pass under the crisp stroke: the line reads as lit rather
        // than as a 2px hairline on a dark card, which is the iOS chart's feel.
        drawPath(
            path = line,
            color = colors.accent.copy(alpha = 0.22f),
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
        )
        drawPath(
            path = line,
            color = colors.accentBright,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // The peak gets a marker. Without one the eye has nothing to land on and
        // a 30-point line is just texture; with it the chart answers "when was
        // the worst day" at a glance.
        val peak = points.minByOrNull { it.y }
        if (peak != null && maximum.signum() > 0) {
            drawCircle(
                color = colors.accentBright.copy(alpha = 0.25f),
                radius = TREND_MARKER_RADIUS.toPx() * 1.9f,
                center = peak
            )
            drawCircle(
                color = colors.accentBright,
                radius = TREND_MARKER_RADIUS.toPx(),
                center = peak
            )
            drawCircle(
                color = colors.paper,
                radius = TREND_MARKER_RADIUS.toPx() * 0.42f,
                center = peak
            )
        }
    }
}

/** The dot marking the highest point of the trend line. */
private val TREND_MARKER_RADIUS = 3.5.dp

/**
 * A legend of the top categories, each row toggling selection.
 *
 * [columns] mirrors the two iOS layouts: the daily ring puts its legend BELOW a
 * full-width chart in two columns (`LazyVGrid`), while the category breakdown
 * puts it BESIDE a small ring in one. A single-column legend under the wide
 * daily ring left half the card empty and pushed the rows off the card.
 */
@Composable
fun CategoryLegend(
    totals: List<CategoryTotal>,
    selectedCategory: String?,
    onSelect: (String?) -> Unit,
    showAmounts: Boolean,
    modifier: Modifier = Modifier,
    columns: Int = 1
) {
    val shown = totals.take(6)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Chunked into rows rather than a LazyVerticalGrid: this sits inside a
        // vertically scrolling parent, and nesting a lazy grid in one throws on
        // unbounded height. The list is capped at six, so there is nothing to
        // virtualise anyway.
        shown.indices.chunked(columns).forEach { rowIndices ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowIndices.forEach { index ->
                    val item = shown[index]
                    val selected = selectedCategory == item.category
                    LegendRow(
                        label = item.category,
                        amount = if (showAmounts) item.total else null,
                        dotColor = ringColor(index),
                        selected = selected,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                onSelect(if (selected) null else item.category)
                            }
                    )
                }
                // Keeps a trailing odd item at one column's width instead of
                // letting it stretch across the whole row.
                repeat(columns - rowIndices.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** The selected slice's name, amount and share of the total. */
@Composable
fun SelectionCallout(
    item: CategoryTotal,
    total: BigDecimal,
    index: Int,
    modifier: Modifier = Modifier
) {
    val colors = Tabby.colors
    val percent = if (total.signum() <= 0) {
        BigDecimal.ZERO
    } else {
        item.total.multiply(BigDecimal(100)).divide(total, 0, RoundingMode.HALF_UP)
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(ringColor(index), androidx.compose.foundation.shape.CircleShape)
        )
        Column {
            Text(
                text = item.category,
                color = colors.ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = "${CurrencyFormat.full(item.total)} · ${percent.toPlainString()}%",
                color = colors.subtleInk,
                fontSize = 11.sp,
                maxLines = 1,
                // The slice callout's amount is money, so it takes the rounded
                // face and tabular figures like every other amount.
                style = moneyStyle()
            )
        }
    }
}
