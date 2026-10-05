package com.maghizhan.tabby.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExtendedFloatingActionButton
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
import com.maghizhan.tabby.ui.theme.TabbyShapes

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
                TabbyOrbit(size = 30.dp, lineWidth = 2.dp)
                Text(
                    text = "Tabby",
                    color = colors.ink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onProfile) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = "Profile",
                        tint = colors.accentBright
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
                    Text(
                        text = "RECENT ACTIVITY",
                        color = colors.subtleInk,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
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
            shape = TabbyShapes.control,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
            Text(
                text = "Add spend",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
