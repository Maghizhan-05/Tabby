package com.maghizhan.tabby.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

    Canvas(
        modifier = modifier.semantics {
            contentDescription = "Spending by category: $description"
        }
    ) {
        val stroke = size.minDimension / 2f * (1f - innerRadiusRatio)
        val diameter = size.minDimension - stroke
        val topLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f
        )

        // A single slice would otherwise be drawn as an arc with a 1.6° gap and
        // two rounded ends, which reads as a ring that failed to close. At 100%
        // there is no neighbour to inset away from, so it is drawn whole.
        val single = totals.count { it.total.signum() > 0 } == 1

        var startAngle = -90f
        totals.forEachIndexed { index, item ->
            val fraction = item.total
                .divide(total, 8, RoundingMode.HALF_UP)
                .toFloat()
            val sweep = fraction * 360f
            val dimmed = selectedCategory != null && selectedCategory != item.category

            if (single) {
                drawArc(
                    color = ringColor(index).copy(alpha = if (dimmed) 0.35f else 1f),
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
                // constant whether the ring is the 150dp daily one or the 104dp
                // breakdown one — a fixed degree inset looks twice as wide on
                // the small ring.
                val insetDegrees = (stroke / 2f) / (diameter / 2f) * (180f / Math.PI.toFloat())
                val drawn = (sweep - insetDegrees * 2f).coerceAtLeast(0.6f)
                drawArc(
                    color = ringColor(index).copy(alpha = if (dimmed) 0.35f else 1f),
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
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        totals.forEach { point ->
            val fraction = if (maximum.signum() <= 0) {
                0f
            } else {
                point.total.divide(maximum, 6, RoundingMode.HALF_UP).toFloat()
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            (
                                if (point.total.signum() > 0) {
                                    (fraction * 120f).coerceAtLeast(4f)
                                } else {
                                    0f
                                }
                                ).dp
                        )
                        .background(
                            colors.accent,
                            androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        )
                )
                Text(
                    text = point.label,
                    color = colors.subtleInk,
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

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
        val points = totals.mapIndexed { index, point ->
            val fraction = if (maximum.signum() <= 0) {
                0f
            } else {
                point.total.divide(maximum, 6, RoundingMode.HALF_UP).toFloat()
            }
            Offset(
                x = size.width * index / (totals.size - 1).toFloat(),
                y = size.height * (1f - fraction)
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
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(colors.accent.copy(alpha = 0.25f), colors.accent.copy(alpha = 0.02f))
            )
        )

        val line = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
            path = line,
            color = colors.accent,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

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
                maxLines = 1
            )
        }
    }
}
