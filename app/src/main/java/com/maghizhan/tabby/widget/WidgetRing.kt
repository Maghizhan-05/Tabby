package com.maghizhan.tabby.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.maghizhan.tabby.ui.home.ringColor
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Renders the widget's category ring to a bitmap.
 *
 * A bitmap rather than a composable: Glance does not run Compose's drawing
 * layer. A widget's UI is marshalled to the host process as a `RemoteViews`
 * tree, which has no canvas primitive, so `Canvas`/`drawArc` — what the in-app
 * [com.maghizhan.tabby.ui.home.CategoryDonut] uses — simply does not exist
 * here. The ring is therefore rasterised in our process and sent across as an
 * image, which is the only way to put a real chart on an Android home screen.
 *
 * Kept deliberately small (see [sizePx] callers) because every bitmap in a
 * `RemoteViews` crosses an IPC boundary with a hard 1MB transaction limit; a
 * full-resolution ring would risk `TransactionTooLargeException` and the
 * widget failing to draw at all.
 */
internal object WidgetRing {

    /**
     * Draws [slices] as a donut ring [sizePx] square.
     *
     * Returns null when there is nothing to draw, so the caller can render the
     * empty state rather than a blank square.
     */
    fun render(
        slices: List<WidgetCategorySlice>,
        sizePx: Int,
        strokePx: Float
    ): Bitmap? {
        val positive = slices.filter { it.total.signum() > 0 }
        val total = positive.fold(BigDecimal.ZERO) { sum, slice -> sum.add(slice.total) }
        if (positive.isEmpty() || total.signum() <= 0 || sizePx <= 0) return null

        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            // ROUND, not BUTT. The iOS ring's arcs end in a half-circle, and
            // square-ended arcs are the single clearest "this is a chart
            // library" tell at widget scale — the shape of the cap is most of
            // the difference between the two rings at a glance.
            strokeCap = Paint.Cap.ROUND
        }

        val inset = strokePx / 2f
        val box = RectF(inset, inset, sizePx - inset, sizePx - inset)

        // An unfilled track behind the arcs, as on iOS. Without it a ring made
        // of one or two slices reads as a broken circle rather than a complete
        // dial with a portion filled; the track is what says "this is the whole,
        // and this much of it is yours".
        val track = Paint(paint).apply {
            color = TRACK_COLOR
            strokeCap = Paint.Cap.BUTT
        }
        canvas.drawArc(box, 0f, 360f, false, track)

        // Proportions are divided as BigDecimal and only converted to float at
        // the draw call, so rounding cannot accumulate across slices and leave a
        // visible gap in the ring — the same rule as the in-app chart.
        var startAngle = -90f
        val single = positive.size == 1
        positive.forEachIndexed { index, slice ->
            val sweep = slice.total
                .divide(total, 8, RoundingMode.HALF_UP)
                .toFloat() * 360f

            paint.color = ringColor(index).toArgb()
            if (single) {
                // No neighbour to inset away from: a gap here would read as a
                // ring that failed to close. Drawn BUTT-capped for the same
                // reason — round caps on a full 360° arc overlap themselves at
                // the seam and render as a visible lump.
                val closed = Paint(paint).apply { strokeCap = Paint.Cap.BUTT }
                canvas.drawArc(box, startAngle, 360f, false, closed)
            } else {
                // The gap scales with the stroke rather than being a fixed 2°.
                // Round caps already eat into the arc by half a stroke at each
                // end, so a constant gap that looked right on an 86dp ring
                // swallowed thin slices whole on a 44dp one.
                val gap = (GAP_DEGREES_AT_UNIT_STROKE * strokePx / sizePx).coerceIn(1.5f, 6f)
                canvas.drawArc(
                    box,
                    startAngle + gap / 2f,
                    (sweep - gap).coerceAtLeast(1f),
                    false,
                    paint
                )
            }
            startAngle += sweep
        }

        return bitmap
    }

    /**
     * The unfilled remainder of the dial: white at ~7%, matching the iOS
     * ring's track against the same near-black card.
     */
    private const val TRACK_COLOR = 0x12FFFFFF.toInt()

    /** Gap between slices, expressed per unit of stroke-to-diameter ratio. */
    private const val GAP_DEGREES_AT_UNIT_STROKE = 18f

    private fun createBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
}
