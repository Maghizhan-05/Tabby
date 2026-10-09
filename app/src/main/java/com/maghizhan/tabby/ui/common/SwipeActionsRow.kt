package com.maghizhan.tabby.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.ui.theme.Tabby
import kotlin.math.abs
import kotlin.math.roundToInt

/** One revealed action: an icon, a label, and the tint of its panel. */
data class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val background: Color,
    val contentColor: Color,
    val onClick: () -> Unit
)

/**
 * Tracks which row in a list currently has its actions revealed.
 *
 * Hoisted out of the rows so a list can enforce "one open at a time" and close
 * the open row when it scrolls. Identity-keyed rather than index-keyed: an index
 * would point at a different expense after a delete and close the wrong row.
 */
@Stable
class SwipeRevealController {
    var openRow: Any? by mutableStateOf(null)
        private set

    /**
     * True while any row is revealed.
     *
     * Read by the screen so an overlay (the Add-spend FAB) can get out of the
     * way: the actions sit at the row's trailing edge, which is exactly where a
     * bottom-end FAB floats, so a revealed Edit/Delete was being covered by it
     * on every row except the last — the only one `contentPadding` protects.
     */
    val isAnyRowOpen: Boolean
        get() = openRow != null

    fun open(row: Any) {
        openRow = row
    }

    /** Releases the reveal, but only if [row] is the one still holding it. */
    fun close(row: Any) {
        if (openRow === row) openRow = null
    }

    fun closeAll() {
        openRow = null
    }
}

/** Remembers a [SwipeRevealController] for a list of swipeable rows. */
@Composable
fun rememberSwipeRevealController(): SwipeRevealController = remember { SwipeRevealController() }

/** Width of one revealed action panel, matching the iOS swipe action button. */
private val ACTION_WIDTH = 72.dp

/** Past this fraction of the full reveal, letting go opens rather than closes. */
private const val OPEN_THRESHOLD = 0.4f

/**
 * A row whose trailing actions are revealed by swiping it left.
 *
 * The Android port of SwiftUI's `.swipeActions(edge: .trailing)`. Replaces the
 * pair of always-visible icon buttons the list used to carry: two controls on
 * every row competed with the amount for the eye and made a quiet list look
 * busy, which is the single biggest reason the list read as unfinished next to
 * iOS. Hiding them behind a swipe keeps the row to content only.
 *
 * Deliberately NOT `SwipeToDismissBox`: that gives one action per direction and
 * a full-swipe dismiss, so Delete would fire on an over-swipe against a
 * financial record. Here the row settles at a reveal and the user must still
 * tap the action, which is also what iOS does with `allowsFullSwipe: false`.
 *
 * The actions stay reachable without the gesture: each is published as a
 * semantics custom action, so TalkBack and switch access get them from the
 * row's context menu rather than needing a drag they cannot perform.
 */
@Composable
fun SwipeActionsRow(
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /**
     * Shared across a list so only ONE row can be open.
     *
     * Without it every row the user swiped stayed open behind them, and the
     * list filled up with exposed Delete buttons — the opposite of the quiet
     * surface this is meant to be. Each row claims the controller by identity
     * when it opens, which closes whichever row held it.
     */
    controller: SwipeRevealController? = null,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val rowKey = remember { Any() }
    val maxOffsetPx = with(density) { (ACTION_WIDTH * actions.size).toPx() }

    // Raw drag position; the rendered offset is animated off it so a fling
    // settles smoothly instead of snapping.
    var offsetPx by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = offsetPx,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
        label = "swipeOffset"
    )

    val close = { offsetPx = 0f }

    // Another row opened, or the list scrolled: give up the reveal.
    if (controller != null && controller.openRow != rowKey && offsetPx != 0f) {
        offsetPx = 0f
    }
    val revealFraction = if (maxOffsetPx == 0f) 0f else abs(animatedOffset) / maxOffsetPx

    Box(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                customActions = actions.map { action ->
                    CustomAccessibilityAction(action.label) {
                        action.onClick()
                        true
                    }
                }
            }
    ) {
        // The action panels sit UNDER the row and are only as tall as it is, so
        // a short row cannot show a stripe of colour below its own content.
        //
        // Rendered ONLY while the row is displaced. The row above them is
        // transparent at rest (so the backdrop shows through the list), which
        // means a panel drawn at rest is simply visible — a permanent red
        // Delete block sitting in the list, which is worse than the icon
        // buttons this replaced.
        if (revealFraction > 0f) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .height(with(density) { rowHeightPx.toDp() }),
            horizontalArrangement = Arrangement.End
        ) {
            actions.forEach { action ->
                ActionPanel(
                    action = action,
                    revealFraction = revealFraction,
                    onInvoke = {
                        // A firmer confirm tick than the threshold crossing:
                        // invoking an action is a committed act (one of them
                        // deletes a financial record), so it must not feel the
                        // same as sliding past a latch.
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        close()
                        controller?.close(rowKey)
                        action.onClick()
                    }
                )
            }
        }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .onSizeChanged { rowHeightPx = it.height.toFloat() }
                .fillMaxWidth()
                // Opaque ONLY while the row is displaced. At rest the row must
                // be transparent so the backdrop's gradient and grid show
                // through it; painting every row with `paper` turned the list
                // into a stack of black bands on a warm backdrop.
                .background(
                    if (animatedOffset == 0f) Color.Transparent else Tabby.colors.paper
                )
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val next = (offsetPx + delta).coerceIn(-maxOffsetPx, 0f)
                        // One tick as the drag crosses the commit threshold, in
                        // either direction. This is the whole of what makes the
                        // gesture feel decided rather than loose: the user is
                        // told where the latch is WHILE dragging, instead of
                        // discovering on release whether it took. Fired on the
                        // crossing only, never per-frame.
                        val wasPast = abs(offsetPx) > maxOffsetPx * OPEN_THRESHOLD
                        val isPast = abs(next) > maxOffsetPx * OPEN_THRESHOLD
                        if (wasPast != isPast) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        // Clamped to the reveal: the row cannot be dragged
                        // right past its resting place, nor left past the
                        // actions into empty space.
                        offsetPx = next
                    },
                    onDragStarted = { controller?.open(rowKey) },
                    onDragStopped = {
                        val opened = abs(offsetPx) > maxOffsetPx * OPEN_THRESHOLD
                        offsetPx = if (opened) -maxOffsetPx else 0f
                        if (!opened) controller?.close(rowKey)
                    }
                )
                .then(
                    if (onClick == null) {
                        Modifier
                    } else {
                        // An open row closes on tap instead of opening the
                        // editor: tapping the visible part of a row whose
                        // actions are showing means "put it back".
                        Modifier.quietClickable {
                            if (abs(offsetPx) > 0f) {
                                close()
                                controller?.close(rowKey)
                            } else {
                                onClick()
                            }
                        }
                    }
                )
        ) { content() }
    }
}

/**
 * Hides an overlay while a swipe row's actions are revealed.
 *
 * The actions appear at the row's TRAILING edge — exactly where a bottom-end
 * FAB floats — so a revealed Edit/Delete sits under the button on every row
 * except the last, which is the only one a list's `contentPadding` clears.
 *
 * Yielding rather than relocating: moving the FAB elsewhere trades this
 * collision for a worse permanent position, and insetting the whole list leaves
 * a dead gutter on every frame to solve a problem that exists only during a
 * gesture. The gesture that reveals the actions is the one that hides the
 * button, and it returns the instant the row closes, so the cost is zero taps.
 *
 * Applied via [Modifier] rather than copied into each screen: Home and Friends
 * both carry this FAB, and a fix that lives in one of them is a fix that drifts.
 */
@Composable
fun Modifier.yieldToSwipeActions(controller: SwipeRevealController): Modifier {
    val hidden = controller.isAnyRowOpen
    val alpha by animateFloatAsState(
        targetValue = if (hidden) 0f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "overlayYieldAlpha"
    )
    val shift by animateDpAsState(
        targetValue = if (hidden) 28.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "overlayYieldShift"
    )
    return this
        .offset(y = shift)
        .alpha(alpha)
        // Fully faded is not merely invisible: a 0-alpha button still takes
        // taps, so without this the FAB would swallow presses aimed at the
        // Delete panel underneath it — the original defect, now silent.
        .then(if (alpha == 0f) Modifier.noTouch() else Modifier)
}

/** Swallows nothing and receives nothing; used to disable a faded overlay. */
private fun Modifier.noTouch(): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { }
}

/** A single revealed action; it scales in as the row slides away from it. */
@Composable
private fun ActionPanel(
    action: SwipeAction,
    revealFraction: Float,
    onInvoke: () -> Unit
) {
    // Content fades and lifts in over the first half of the reveal, so a
    // half-open row hints at the action rather than showing it fully formed.
    val appear = (revealFraction * 2f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .width(ACTION_WIDTH)
            .fillMaxHeight()
            .padding(vertical = 2.dp, horizontal = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(action.background)
            .quietClickable(onClick = onInvoke),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = action.contentColor.copy(alpha = appear),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = action.label,
                color = action.contentColor.copy(alpha = appear),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * A click with no ripple.
 *
 * A ripple on a row that is also draggable flashes on every swipe, which reads
 * as a mis-tap; the iOS rows use `.buttonStyle(.plain)` for the same reason.
 */
@Composable
private fun Modifier.quietClickable(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick
)
