package com.maghizhan.tabby.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.ui.format.CurrencyFormat
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyOrbit
import com.maghizhan.tabby.ui.theme.TabbyShapes
import com.maghizhan.tabby.ui.theme.moneyStyle
import java.math.BigDecimal

/** The card surface every analytics and list panel sits on. */
@Composable
fun TabbyCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = Tabby.colors
    Box(
        modifier = modifier
            // A soft drop shadow under the card. On iOS the card reads as a pane
            // LIFTED off the backdrop; a flat fill with a hairline border made
            // the Android card look painted onto the background, which is most
            // of why the screen felt less considered. Ambient/spot are tinted to
            // near-black rather than Material's default grey-blue haze.
            .shadow(
                elevation = 18.dp,
                shape = TabbyShapes.card,
                ambientColor = CARD_SHADOW,
                spotColor = CARD_SHADOW
            )
            // The fill goes INSIDE a vertical gradient: a single flat colour on a
            // 400dp-tall card bands visibly against the backdrop's own gradient.
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.surface.copy(alpha = 0.92f),
                        colors.surface.copy(alpha = 0.74f)
                    )
                ),
                TabbyShapes.card
            )
            .border(1.dp, colors.hairline, TabbyShapes.card)
            .padding(18.dp)
    ) { content() }
}

/** Near-black card shadow; Material's default grey reads as haze on this palette. */
private val CARD_SHADOW = Color(0xFF000000)

/**
 * The size the analytics amount is rendered at, for a given rendered length.
 *
 * Compose (on this BOM) has no `autoSize` text and no equivalent of the iOS
 * `minimumScaleFactor(0.5)`, so a long amount — "12,34,567.89" is twelve
 * glyphs — either wraps or is clipped at a fixed size. The step-down reproduces
 * iOS's behaviour: the headline renders at the full 40sp until it stops fitting
 * and then shrinks, never below half, which is iOS's floor.
 *
 * A pure function rather than a layout trick so the rule is unit-testable; a
 * headline that silently clips the user's money is not something to leave to a
 * visual check.
 */
object AmountTypography {
    /** iOS `.system(size: 40, weight: .bold)`. */
    const val BASE_SP = 40f

    /** iOS `minimumScaleFactor(0.5)`. */
    const val MINIMUM_SP = BASE_SP / 2f

    /** Glyphs that fit at [BASE_SP] across a phone-width analytics card. */
    const val COMFORTABLE_LENGTH = 9

    fun fontSizeSp(renderedLength: Int): Float {
        if (renderedLength <= COMFORTABLE_LENGTH) return BASE_SP
        val scaled = BASE_SP * COMFORTABLE_LENGTH / renderedLength.toFloat()
        return scaled.coerceAtLeast(MINIMUM_SP)
    }
}

/** The dominant amount typography used across analytics headers. */
@Composable
fun AmountHeadline(title: String, amount: BigDecimal, modifier: Modifier = Modifier) {
    val colors = Tabby.colors
    val rendered = CurrencyFormat.full(amount)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title.uppercase(),
            color = colors.subtleInk,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        Text(
            text = rendered,
            color = colors.ink,
            // 40sp, matching iOS, instead of the 34sp this was: the headline is
            // the single dominant element of the card on both platforms.
            fontSize = AmountTypography.fontSizeSp(rendered.length).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            // Digits share one advance width, so a ticking total does not make
            // the headline jitter — the iOS `.monospacedDigit()`.
            style = moneyStyle(),
            textAlign = TextAlign.Center
        )
    }
}

/** Shown in place of a chart when the selected period has no spending. */
@Composable
fun EmptyAnalytics(modifier: Modifier = Modifier) {
    val colors = Tabby.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // 6dp between the orbit and the caption, as on iOS. The previous
        // `Spacer(Modifier.width(6.dp))` set a HORIZONTAL size inside a Column,
        // so it contributed no gap at all and the caption sat against the mark.
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        // .opacity(0.8) on iOS: the empty-state mark is quieter than the header's.
        TabbyOrbit(size = 34.dp, lineWidth = 2.dp, modifier = Modifier.alpha(0.8f))
        Text(text = "No spending yet", color = colors.subtleInk, fontSize = 13.sp)
    }
}

/** A legend row: colour dot, category name, amount. */
@Composable
fun LegendRow(
    label: String,
    amount: BigDecimal?,
    dotColor: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = Tabby.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selected) colors.elevatedSurface else androidx.compose.ui.graphics.Color.Transparent,
                TabbyShapes.control
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, androidx.compose.foundation.shape.CircleShape)
        )
        Text(
            text = label,
            color = colors.ink,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            // Ellipsis rather than a hard clip, so a long category name reads as
            // shortened instead of looking like a different word ("Tran").
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (amount != null) {
            Text(
                // Full currency, as on iOS — the breakdown legend is the place
                // the user reads exact per-category spend; `compact` turned
                // 12,340 into "12.3K" and lost the exact figure.
                text = CurrencyFormat.full(amount),
                color = colors.subtleInk,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                style = moneyStyle(),
                // The amount never shrinks for the label; it is the data.
                softWrap = false
            )
        }
    }
}
