package com.maghizhan.tabby.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

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
 * Mechanics: draw is suppressed until a measurement fits, so the user never
 * sees the intermediate sizes flash. State is keyed on [text] and [fontSize] so
 * a recomposition with new content re-measures from the top rather than
 * inheriting the previous label's shrink.
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
    style: TextStyle = TextStyle.Default
) {
    var resolvedSize by remember(text, fontSize) { mutableStateOf(fontSize) }
    var measured by remember(text, fontSize) { mutableStateOf(false) }

    Text(
        text = text,
        color = color,
        fontSize = resolvedSize,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        textAlign = textAlign,
        maxLines = 1,
        // Must be false: with wrapping on, an over-wide label breaks at its
        // space and `maxLines = 1` then hides the rest — the exact failure this
        // composable exists to prevent. Off, the overflow is reported instead.
        softWrap = false,
        style = style,
        onTextLayout = { result ->
            if (result.didOverflowWidth && resolvedSize > minFontSize) {
                // Half-point steps: whole points visibly mismatch neighbouring
                // columns' labels, and the loop runs at most a handful of times
                // over a 20% range.
                resolvedSize = (resolvedSize.value - 0.5f).sp
            } else {
                measured = true
            }
        },
        modifier = modifier.drawWithContent { if (measured) drawContent() }
    )
}
