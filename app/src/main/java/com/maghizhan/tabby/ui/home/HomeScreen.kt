package com.maghizhan.tabby.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.ui.common.TabbyCard
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyOrbit

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
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TabbyOrbit(size = 28.dp, lineWidth = 2.5.dp)
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
                        tint = colors.subtleInk
                    )
                }
            }

            // Card and list share ONE LazyColumn rather than the card being
            // fixed above a weighted list. With a split, the tall analytics card
            // claimed the space and the list was squeezed to a sliver that the
            // FAB then covered — observed on a device. Scrolling them together
            // means the chart can move out of the way when the user wants rows.
            LazyColumn(
                modifier = Modifier.weight(1f),
                // Clears the FAB so the last row's Edit/Delete stay tappable.
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "analytics") {
                    TabbyCard(modifier = Modifier.fillMaxWidth()) {
                        AnalyticsPanel(
                            expenses = expenses,
                            mode = mode,
                            selectedCategory = selectedCategory,
                            onModeSelected = onModeSelected,
                            onCategorySelected = onCategorySelected
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

        ExtendedFloatingActionButton(
            onClick = onAddSpend,
            containerColor = colors.accent,
            contentColor = colors.paper,
            // A capsule, not the 16dp control shape: iOS uses `Capsule()` here.
            shape = CircleShape,
            // The gold glow under the button, matching the iOS
            // `.shadow(accentGlow, radius: 16, y: 6)`.
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 10.dp,
                pressedElevation = 6.dp
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(22.dp)
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
