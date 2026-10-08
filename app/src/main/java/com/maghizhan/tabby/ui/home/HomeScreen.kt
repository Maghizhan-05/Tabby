package com.maghizhan.tabby.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.ui.common.TabbyCard
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyOrbit

/**
 * The share of the home canvas the analytics card may claim.
 *
 * The iOS `HomeView` pins its card to `geo.size.height * 0.49`. Matching the
 * fraction rather than a dp value keeps the two apps proportional across every
 * screen size instead of only on the device this was checked against.
 */
private const val IOS_ANALYTICS_HEIGHT_FRACTION = 0.49f

/**
 * Home: the analytics card over recent activity, with a quick-entry FAB.
 *
 * Stateless with respect to data — expenses and selections arrive as
 * parameters — so the screen can be rendered in a preview or a UI test without
 * a database behind it.
 */
@Composable
fun HomeScreen(
    expenses: List<ExpenseEntity>,
    mode: AnalyticsMode,
    selectedCategory: String?,
    onModeSelected: (AnalyticsMode) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onProfile: () -> Unit,
    onAddSpend: () -> Unit,
    onEditExpense: (ExpenseEntity) -> Unit,
    onDeleteExpense: (ExpenseEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Tabby.colors

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 16dp, matching the iOS `.padding(.horizontal, 16)`.
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    // The iOS header insets its contents a further 4dp inside
                    // the screen's 16dp margin.
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TabbyOrbit(size = 26.dp, lineWidth = 2.5.dp)
                Text(
                    text = "Tabby",
                    color = colors.ink,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onProfile) {
                    Icon(
                        imageVector = Icons.Outlined.AccountCircle,
                        contentDescription = "Profile",
                        // subtleInk, not gold: on iOS the profile glyph is quiet
                        // chrome, and gold is reserved for focus and
                        // confirmation. A gold icon here competed with the FAB.
                        tint = colors.subtleInk,
                        // iOS `.font(.title2)` — a 22pt glyph, not Material's 24.
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Card and list share ONE LazyColumn rather than the card being
            // fixed above a weighted list. With a split, the tall analytics card
            // claimed the space and the list was squeezed to a sliver that the
            // FAB then covered — observed on a device. Scrolling them together
            // means the chart can move out of the way when the user wants rows.
            //
            // The card is FIXED at the iOS proportion of the WHOLE SCREEN, not
            // of the space left under the header. Measuring the leftover instead
            // gave the card ~235dp, of which the selector, headline and legend
            // took all but ~30dp, and the ring rendered as a dot — seen on
            // device. Fixed rather than capped, too: a `heightIn(max=)` card
            // still wraps its content, leaving the chart's weight nothing to claim.
            val analyticsHeight =
                LocalConfiguration.current.screenHeightDp.dp * IOS_ANALYTICS_HEIGHT_FRACTION

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Clears the FAB so the last row's Edit/Delete stay tappable.
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item(key = "analytics") {
                        TabbyCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(analyticsHeight)
                        ) {
                            AnalyticsPanel(
                                expenses = expenses,
                                mode = mode,
                                selectedCategory = selectedCategory,
                                onModeSelected = onModeSelected,
                                onCategorySelected = onCategorySelected,
                                // Fill the capped card rather than sizing the
                                // chart to a fixed 160dp: the ring then shrinks
                                // on a small screen instead of being clipped by
                                // the cap, and leaves no dead space on a large one.
                                fillHeight = true
                            )
                        }
                    }

                    item(key = "recent-header") {
                        // Title-case headline with the gold LIVE badge, as on iOS.
                        // The all-caps 11sp micro-label that was here read as a form
                        // field label rather than a section heading.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recent activity",
                                color = colors.ink,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "LIVE",
                                color = colors.accentBright,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    recentEntries(
                        expenses = expenses,
                        onEdit = onEditExpense,
                        onDelete = onDeleteExpense
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAddSpend,
            containerColor = colors.accent,
            contentColor = colors.paper,
            // A capsule, not the 16dp control shape: iOS uses `Capsule()` here.
            shape = CircleShape,
            // Material's own elevation draws a BLACK scrim under the button. The
            // iOS FAB casts a gold glow instead, which is reproduced by the
            // tinted `Modifier.shadow` below, so the component elevation is
            // taken to zero rather than stacking a grey shadow under the gold.
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = 22.dp)
                // The iOS `.shadow(color: accentGlow, radius: 16, y: 6)`.
                // Tinted shadows are an API 28+ feature and degrade to the
                // platform default below it, which is why the glow is expressed
                // here rather than as a drawn halo.
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = colors.accentGlow,
                    spotColor = colors.accent
                )
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
            Text(
                text = "Add spend",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
