package com.maghizhan.tabby.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.data.local.entity.FriendEntity
import com.maghizhan.tabby.ui.common.AutoShrinkText
import com.maghizhan.tabby.ui.common.TabbyCard
import androidx.compose.ui.graphics.Color
import com.maghizhan.tabby.ui.common.SwipeAction
import com.maghizhan.tabby.ui.common.SwipeActionsRow
import com.maghizhan.tabby.ui.common.rememberSwipeRevealController
import com.maghizhan.tabby.ui.format.CurrencyFormat
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyOrbit
import com.maghizhan.tabby.ui.theme.TabbyShapes
import com.maghizhan.tabby.ui.theme.moneyStyle
import java.math.BigDecimal

/**
 * Friends: a who-owes-whom table with an aggregate net footer.
 *
 * Amounts are right-aligned in fixed-weight columns so the decimal points line
 * up down the list — a money table where the digits wander is hard to scan and
 * easy to misread.
 */
@Composable
fun FriendsScreen(
    friends: List<FriendEntity>,
    aggregateNet: BigDecimal,
    onAdd: () -> Unit,
    onEdit: (FriendEntity) -> Unit,
    onDelete: (FriendEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Tabby.colors
    val swipeController = rememberSwipeRevealController()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 16dp, matching Home and the iOS screen margin.
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Same mark geometry as Home's header; 28dp/2dp here made the
                // two tabs' titles sit at visibly different heights.
                TabbyOrbit(size = 26.dp, lineWidth = 2.5.dp)
                Text(
                    text = "Friends",
                    color = colors.ink,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            TabbyCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "NET BALANCE",
                        color = colors.subtleInk,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = CurrencyFormat.signed(aggregateNet),
                        color = when {
                            aggregateNet.signum() > 0 -> colors.accentBright
                            // Red, as on iOS: "you owe overall" is not neutral
                            // ink, and ink alone left the two directions
                            // indistinguishable at a glance.
                            aggregateNet.signum() < 0 -> colors.negative
                            else -> colors.subtleInk
                        },
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        style = moneyStyle()
                    )
                    Text(
                        text = when {
                            aggregateNet.signum() > 0 -> "Owed to you overall"
                            aggregateNet.signum() < 0 -> "You owe overall"
                            else -> "All square"
                        },
                        color = colors.subtleInk,
                        fontSize = 12.sp
                    )
                }
            }

            if (friends.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    TabbyOrbit(size = 32.dp, lineWidth = 2.dp)
                    Text(
                        text = "No friends tracked",
                        color = colors.ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Text(
                        text = "Add someone to start settling up.",
                        color = colors.subtleInk,
                        fontSize = 12.sp
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeaderCell("FRIEND", weight = 1.6f, align = TextAlign.Start)
                    HeaderCell("THEY OWE", weight = 1f, align = TextAlign.End)
                    HeaderCell("YOU OWE", weight = 1f, align = TextAlign.End)
                    HeaderCell("NET", weight = 1f, align = TextAlign.End)
                    // No trailing spacer any more: the delete button it used to
                    // align with is now a swipe action, so NET runs to the edge
                    // exactly as its values do.
                }
                HorizontalDivider(color = colors.hairline)

                val listState = rememberLazyListState()
                LaunchedEffect(listState.isScrollInProgress) {
                    if (listState.isScrollInProgress) swipeController.closeAll()
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    // Same reason as Home: the FAB would otherwise cover the
                    // last friend's delete button.
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(items = friends, key = { it.id }) { friend ->
                        val net = friend.netBalance
                        // Swipe to delete, same gesture as the Home list. A
                        // permanent trash icon in every row is the Android
                        // habit the iOS app deliberately does not have: it puts
                        // a destructive control one mis-tap away and steals the
                        // width the NET column needs.
                        SwipeActionsRow(
                            controller = swipeController,
                            onClick = { onEdit(friend) },
                            actions = listOf(
                                SwipeAction(
                                    label = "Delete",
                                    icon = Icons.Filled.Delete,
                                    background = colors.negative,
                                    contentColor = Color.White,
                                    onClick = { onDelete(friend) }
                                )
                            )
                        ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.weight(1.6f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .background(colors.accent.copy(alpha = 0.18f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = friend.name.take(1).uppercase(),
                                        color = colors.accentBright,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = friend.name,
                                    color = colors.ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                            AmountCell(friend.theyOweUs, colors.accentBright, 1f)
                            AmountCell(friend.weOweThem, colors.ink, 1f)
                            Text(
                                text = CurrencyFormat.signed(net),
                                // Red for a negative net, as on iOS; ink made
                                // "they owe you" and "you owe them" look alike.
                                color = if (net.signum() >= 0) colors.accentBright else colors.negative,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                style = moneyStyle(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        }
                        HorizontalDivider(color = colors.hairline)
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAdd,
            containerColor = colors.accent,
            contentColor = colors.paper,
            // Capsule and gold glow, identical to Home's FAB: two differently
            // shaped primary buttons on sibling tabs read as two apps.
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = 22.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = colors.accentGlow,
                    spotColor = colors.accent
                )
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
            Text(
                text = "Add friend",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(
    text: String,
    weight: Float,
    align: TextAlign
) {
    AutoShrinkText(
        text = text,
        color = Tabby.colors.subtleInk,
        fontSize = 10.sp,
        // 6sp floor rather than 8sp. The floor is in sp, so the system font
        // scale multiplies it while the column width does not grow: at 1.3x an
        // 8sp floor still overflowed and, with softWrap off, hard-clipped to
        // "THEY OW". 6sp holds the full label to ~1.5x, past which the layout
        // itself is wrong and should be fixed there rather than by shrinking.
        minFontSize = 6.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        textAlign = align,
        modifier = Modifier.weight(weight)
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.AmountCell(
    amount: BigDecimal,
    color: androidx.compose.ui.graphics.Color,
    weight: Float
) {
    Text(
        text = if (amount.signum() == 0) "—" else CurrencyFormat.compact(amount),
        color = if (amount.signum() == 0) Tabby.colors.subtleInk else color,
        fontSize = 14.sp,
        textAlign = TextAlign.End,
        maxLines = 1,
        // Tabular figures so the three money columns line up down the table —
        // the iOS row uses `.monospacedDigit()` for the same reason.
        style = moneyStyle(),
        modifier = Modifier.weight(weight)
    )
}

/** Add / edit a friend: name plus the two balances. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendEditSheet(
    state: FriendEditState,
    onNameChanged: (String) -> Unit,
    onTheyOweUsChanged: (String) -> Unit,
    onWeOweThemChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    val isEditing = state.isEditing
    val colors = Tabby.colors

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.paper) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = if (isEditing) "Edit friend" else "Add friend",
                color = colors.ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChanged,
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.theyOweUsText,
                    onValueChange = onTheyOweUsChanged,
                    label = { Text("They owe") },
                    singleLine = true,
                    isError = FriendsForm.parseBalance(state.theyOweUsText) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.weOweThemText,
                    onValueChange = onWeOweThemChanged,
                    label = { Text("You owe") },
                    singleLine = true,
                    isError = FriendsForm.parseBalance(state.weOweThemText) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface.copy(alpha = 0.7f), TabbyShapes.control)
                    .border(1.dp, colors.hairline, TabbyShapes.control)
                    .padding(14.dp)
            ) {
                val previewNet = (FriendsForm.parseBalance(state.theyOweUsText) ?: BigDecimal.ZERO)
                    .subtract(FriendsForm.parseBalance(state.weOweThemText) ?: BigDecimal.ZERO)
                Text(
                    text = "Net: ${CurrencyFormat.signed(previewNet)}",
                    color = colors.accentBright,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            state.notice?.let {
                Text(text = it, color = colors.accentBright, fontSize = 12.sp)
            }

            Button(
                onClick = onSubmit,
                enabled = state.canSave,
                shape = TabbyShapes.control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.paper,
                    disabledContainerColor = colors.accent.copy(alpha = 0.32f),
                    disabledContentColor = colors.paper.copy(alpha = 0.7f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isEditing) "Save changes" else "Add friend",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
