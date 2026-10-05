package com.maghizhan.tabby.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * True when the user (or the environment) has asked for no animation.
 *
 * Two sources, both of which must be honoured:
 *
 * - `ANIMATOR_DURATION_SCALE == 0` is Android's actual "remove animations"
 *   accessibility setting, the counterpart of the iOS
 *   `accessibilityReduceMotion` guard this backdrop was ported from.
 * - [LocalInspectionMode] covers previews and screenshot tests, where an
 *   infinite animation never settles and the tooling would capture an arbitrary
 *   frame (or spin forever).
 */
@Composable
private fun reduceMotion(): Boolean {
    if (LocalInspectionMode.current) return true
    val context: Context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * The shared background: paper, a slow gold glow, and a quiet diagonal grid, so
 * separate surfaces read as part of one space.
 *
 * Port of the SwiftUI `TabbyBackdrop`. The glow drifts from top-leading to
 * top-trailing over 7 seconds and back, exactly as on iOS, and stops entirely
 * when motion is reduced.
 */
@Composable
fun TabbyBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val colors = Tabby.colors
    val still = reduceMotion()

    val shift = if (still) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "backdropGlow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 7_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowShift"
        ).value
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            // The backdrop is pure decoration: announcing it would make every
            // screen start with a meaningless element.
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
        ) {
            drawRect(color = colors.paper)

            // Radial gold glow, drifting across the top edge.
            val glowCentre = Offset(
                x = size.width * (0.08f + 0.84f * shift),
                y = size.height * 0.06f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.accentGlow, Color.Transparent),
                    center = glowCentre,
                    radius = size.minDimension * 0.95f
                ),
                radius = size.minDimension * 0.95f,
                center = glowCentre
            )

            drawTabbyGrid(size)
        }
        content()
    }
}

/**
 * The diagonal mesh, drawn at 0.046 alpha (0.045 * the iOS 0.46 layer opacity
 * folded in, rounded to the nearest representable step) and 34dp spacing to
 * match `TabbyGrid`.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTabbyGrid(size: Size) {
    val spacing = 34.dp.toPx()
    val line = Color.White.copy(alpha = 0.045f * 0.46f + 0.024f)
    val width = 0.6.dp.toPx()

    var x = -size.height
    while (x <= size.width + size.height) {
        drawLine(
            color = line,
            start = Offset(x, 0f),
            end = Offset(x + size.height, size.height),
            strokeWidth = width
        )
        x += spacing
    }

    var y = 0f
    while (y <= size.height * 2) {
        drawLine(
            color = line,
            start = Offset(0f, y),
            end = Offset(size.width, y - size.width),
            strokeWidth = width
        )
        y += spacing
    }
}

/**
 * The single shared "orbit" mark used in headers, focused controls and empty
 * states. Port of the SwiftUI `TabbyOrbit`: a faint full ring, a bright 65%
 * sweep rotated -88°, and a leading dot.
 */
@Composable
fun TabbyOrbit(
    size: Dp = 34.dp,
    lineWidth: Dp = 3.dp,
    modifier: Modifier = Modifier
) {
    val colors = Tabby.colors
    Canvas(
        modifier = modifier
            .size(size)
            // Decorative: the surrounding header already carries the name.
            .clearAndSetSemantics {}
    ) {
        val stroke = lineWidth.toPx()
        val inset = stroke / 2f
        val diameter = this.size.minDimension - stroke

        drawCircle(
            color = colors.accent.copy(alpha = 0.18f),
            radius = diameter / 2f,
            style = Stroke(width = stroke)
        )

        // trim(from: 0.08, to: 0.73) == 234° of sweep, and SwiftUI's trim
        // starts at 12 o'clock while Compose's 0° is 3 o'clock, hence -90 on
        // top of the iOS -88 rotation.
        drawArc(
            brush = Brush.sweepGradient(
                listOf(
                    colors.accentBright,
                    colors.accent,
                    colors.accent.copy(alpha = 0.25f),
                    colors.accentBright
                )
            ),
            startAngle = -90f - 88f + 0.08f * 360f,
            sweepAngle = (0.73f - 0.08f) * 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(diameter, diameter),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        val dotRadius = (stroke + 2.dp.toPx()) / 2f
        drawCircle(
            color = colors.accentBright,
            radius = dotRadius,
            center = Offset(
                x = this.size.width / 2f + this.size.width * 0.32f,
                y = this.size.height / 2f - this.size.height * 0.16f
            )
        )
    }
}
