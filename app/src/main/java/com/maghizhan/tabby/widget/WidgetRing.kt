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
            strokeCap = Paint.Cap.BUTT
        }

        val inset = strokePx / 2f
        val box = RectF(inset, inset, sizePx - inset, sizePx - inset)

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
                // ring that failed to close.
                canvas.drawArc(box, startAngle, 360f, false, paint)
            } else {
                val gap = 2f
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

    private fun createBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
}
