package com.maghizhan.tabby.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * A single-line label that shrinks itself until it fits, instead of losing
 * words.
 *
 * Why this exists: fixed `fontSize` + `maxLines = 1` is only ever correct for
 * one font, one locale and one screen width. Change any of the three and
 * Compose silently breaks at a space and renders the first line alone — the
 * Friends header showed "THEY" where the source says "THEY OWE" the moment
 * Inter replaced Roboto, because Inter is a hair wider at the same size and the
 * column is ~50dp on a 320dp screen. Nothing warned; the word just vanished.
 *
 * Shrinking rather than ellipsising because these are short structural labels
 * ("THEY OWE", "Category"): "THEY…" is not more readable than the same words a
 * point smaller, and an ellipsis in a table header reads like a defect. The
 * floor stops it becoming unreadable — if a label cannot fit even there, the
 * layout is wrong and should be fixed at the layout.
 *
 * ## Why the fit is measured rather than remembered
 *
 * The obvious implementation — shrink inside `onTextLayout` when overflow is
 * reported, hold the result in state — only ever travels DOWN. Nothing restores
 * the size once the constraint that forced the shrink is gone: rotating into a
 * wider column, lowering the system font scale, or resizing a freeform window
 * all leave the label permanently small, and that staleness is invisible
 * because small-but-complete text looks deliberate.
 *
 * So the size is DERIVED, not stored. [rememberTextMeasurer] measures candidate
 * sizes against the real constraints during composition and the largest that
 * fits is drawn. Any input change — available width, font scale, style, weight,
 * the string itself — re-derives from the top and can travel back UP as readily
 * as down. A derived size also means no intermediate size is ever committed to
 * the tree, so there is no flash needing suppression.
 */
@Composable
fun AutoShrinkText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    minFontSize: TextUnit = fontSize * 0.8f,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    // LocalTextStyle, NOT TextStyle.Default: `Text` merges this over the
    // inherited style, so passing Default would discard the theme's Inter
    // family and render these labels in the platform font — the very
    // regression this component exists to repair.
    style: TextStyle = LocalTextStyle.current
) {
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier) {
        val available = constraints.maxWidth

        // Measured with INFINITE width so the result is the label's natural
        // width. Measuring against the real constraint would let the engine
        // clamp the line and report no overflow, which is the failure mode this
        // replaces rather than a usable signal.
        fun naturalWidthAt(sizeSp: Float): Int = measurer.measure(
            text = AnnotatedString(text),
            style = style.merge(
                TextStyle(
                    fontSize = sizeSp.sp,
                    fontWeight = fontWeight,
                    letterSpacing = letterSpacing
                )
            ),
            maxLines = 1,
            softWrap = false,
            constraints = Constraints()
        ).size.width

        val floor = minFontSize.value
        var candidate = fontSize.value
        // Half-point steps: whole points visibly mismatch the neighbouring
        // columns' labels, and the span is at most ~20% of the base size, so
        // this settles in a handful of iterations.
        while (candidate > floor && naturalWidthAt(candidate) > available) {
            candidate -= 0.5f
        }

        Text(
            text = text,
            color = color,
            fontSize = candidate.coerceAtLeast(floor).sp,
            fontWeight = fontWeight,
            letterSpacing = letterSpacing,
            textAlign = textAlign,
            maxLines = 1,
            // Must stay false: with wrapping on, an over-wide label breaks at
            // its space and `maxLines = 1` then hides the remainder — exactly
            // the defect this composable prevents.
            softWrap = false,
            style = style,
            // Fills the constrained width so textAlign has a box to align
            // WITHIN. Left intrinsic, the Text was only as wide as its glyphs
            // and sat at the box's start, so `textAlign = End` silently did
            // nothing: the Friends "NET" header sat at the start of its column
            // while the net values beneath it were right-aligned.
            modifier = Modifier.fillMaxWidth()
        )
    }
}
