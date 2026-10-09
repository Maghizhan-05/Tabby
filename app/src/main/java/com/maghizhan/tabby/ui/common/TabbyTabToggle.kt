package com.maghizhan.tabby.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.ui.theme.Tabby

/** One destination in a [TabbyTabToggle]. */
data class TabbyTab(
    val label: String,
    val icon: ImageVector,
    val onSelect: () -> Unit
)

/**
 * The two-destination toggle that replaces Material's `NavigationBar`.
 *
 * Material renders Home and Friends as two independent items, each with its own
 * pill indicator appearing and disappearing in place. That reads as two
 * separate buttons — the "cheap copy" complaint in the clearest place it shows,
 * since the bottom bar is on screen on every frame. iOS groups a small, fixed
 * set of destinations into ONE control where a single selection marker MOVES
 * between them, so the two halves read as two states of one switch.
 *
 * The moving pill is the whole point: it is what makes a toggle feel like a
 * toggle rather than two lights. It is implemented as a single [Box] offset
 * across a [BoxWithConstraints], not as per-item backgrounds, so travel is
 * continuous and the user's eye tracks one object.
 *
 * Deliberately NOT general: two destinations only. The pill is sized at an
 * exact half, and a scrolling/variable-count version is a different control
 * with different ergonomics (see `ModeSelector`, which has six modes and must
 * scroll). Taking an arbitrary list here would invite the bar to grow a third
 * tab and silently become unreadable.
 *
 * Accessibility is hand-wired because `NavigationBarItem` supplied it for free
 * and a custom control loses it: each half declares `Role.Tab` and its selected
 * state via [selectable], so TalkBack still announces "Home, tab, selected"
 * rather than reading an undifferentiated row of text.
 */
@Composable
fun TabbyTabToggle(
    tabs: List<TabbyTab>,
    selectedIndex: Int,
    modifier: Modifier = Modifier
) {
    require(tabs.size == 2) { "TabbyTabToggle is a two-destination control; got ${tabs.size}" }
    val colors = Tabby.colors

    BoxWithConstraints(
        modifier = modifier
            .height(TOGGLE_HEIGHT)
            // The glass: a translucent dark capsule with a hairline rim and a
            // top-down sheen. Opaque fill made the bar a slab sitting on the
            // backdrop; letting the gradient show through is what reads as a
            // pane of material rather than a painted rectangle.
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.elevatedSurface.copy(alpha = 0.92f),
                        colors.surface.copy(alpha = 0.88f)
                    )
                )
            )
            .border(BorderStroke(1.dp, colors.hairline), CircleShape)
            .padding(TOGGLE_INSET)
    ) {
        val pillWidth = maxWidth / 2
        // Spring, not tween: the selection marker is the one element the user
        // directly drives, and a linear slide feels mechanical where a spring
        // feels physical. Low bounce — this is a control, not a toy.
        val pillOffset by animateDpAsState(
            targetValue = if (selectedIndex <= 0) 0.dp else pillWidth,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "tabPillOffset"
        )

        Box(
            modifier = Modifier
                .offset(x = pillOffset)
                .width(pillWidth)
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(listOf(colors.accentBright, colors.accent)),
                    CircleShape
                )
        )

        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                // Contents cross-fade on the same beat as the pill's travel, so
                // the label is never dark-on-dark mid-slide.
                val contentColor by animateColorAsState(
                    targetValue = if (selected) colors.paper else colors.subtleInk,
                    animationSpec = tween(durationMillis = 220),
                    label = "tabContentColor"
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            interactionSource = remember { MutableInteractionSource() },
                            // No ripple: a grey Material splash over a gold
                            // capsule is the most obviously un-iOS thing a tap
                            // here can do.
                            indication = null,
                            role = Role.Tab,
                            onClick = tab.onSelect
                        )
                        .semantics { },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = tab.icon,
                        // Null: the adjacent label already carries the name, and
                        // a description here makes TalkBack read every tab twice.
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Box(modifier = Modifier.width(7.dp))
                    Text(
                        text = tab.label,
                        color = contentColor,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 46dp of capsule plus the 3dp inset gives a 52dp control: at or above the 48dp
 * minimum touch target once the inset is counted, while staying visibly lighter
 * than Material's 80dp navigation bar — part of why the stock bar read as heavy
 * Android chrome.
 */
private val TOGGLE_HEIGHT = 46.dp

/** The gap between the pill and the capsule rim, on all four sides. */
private val TOGGLE_INSET = 3.dp

/** Transparent so the toggle floats over the screen's own backdrop. */
internal val TOGGLE_SCRIM: Color = Color.Transparent
