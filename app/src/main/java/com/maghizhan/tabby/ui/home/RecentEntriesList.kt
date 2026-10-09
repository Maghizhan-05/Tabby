package com.maghizhan.tabby.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.ui.common.SwipeAction
import com.maghizhan.tabby.ui.common.SwipeActionsRow
import com.maghizhan.tabby.ui.common.SwipeRevealController
import com.maghizhan.tabby.ui.format.CurrencyFormat
import com.maghizhan.tabby.ui.theme.CategoryAccent
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyOrbit
import com.maghizhan.tabby.ui.theme.moneyStyle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Row title: the note when present, else "<Category> expense" — the iOS rule. */
fun recentEntryTitle(expense: ExpenseEntity): String =
    expense.note?.takeIf { it.isNotBlank() } ?: "${expense.categoryName} expense"

/**
 * The row timestamp's format.
 *
 * iOS writes `.dateTime.month().day().hour().minute()` — a TEMPLATE, which
 * Foundation reorders per locale ("1 Oct at 1:19 AM" in en-IN, "Oct 1" in
 * en-US). Android's equivalent is ICU's `getBestDateTimePattern`, so the same
 * template produces the same locale-correct ordering here rather than a
 * hardcoded day/month guess.
 *
 * Resolved lazily, and guarded: the ICU call is an Android API, and this file's
 * presentation helpers are also exercised by plain JVM unit tests where the
 * framework is a stub. The fallback is the previous fixed pattern.
 */
private val DATE_FORMAT: DateTimeFormatter by lazy {
    val locale = Locale.getDefault()
    val pattern = runCatching {
        android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdjmm")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "MMM d, HH:mm"
    DateTimeFormatter.ofPattern(pattern, locale)
}

/**
 * Recent spending, deliberately quiet so the analytics visual stays primary.
 *
 * Edit and Delete are revealed by swiping a row left, as on iOS, rather than
 * sitting on it as two permanent icon buttons. The buttons were the single
 * biggest reason the list looked unfinished beside iOS: every row carried two
 * controls competing with the amount, so a list of five spends showed ten
 * icons. See [SwipeActionsRow] for why this is not `SwipeToDismissBox` and how
 * the actions stay reachable without the gesture.
 */
fun LazyListScope.recentEntries(
    expenses: List<ExpenseEntity>,
    onEdit: (ExpenseEntity) -> Unit,
    onDelete: (ExpenseEntity) -> Unit,
    /** Shared so only one row's actions are ever revealed. */
    swipeController: SwipeRevealController,
    zone: ZoneId = ZoneId.systemDefault()
) {
    if (expenses.isEmpty()) {
        item(key = "recent-empty") {
            val colors = Tabby.colors
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                TabbyOrbit(size = 32.dp, lineWidth = 2.dp)
                Text(
                    text = "No spends yet",
                    color = colors.ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    text = "Your next tab starts with one tap.",
                    color = colors.subtleInk,
                    fontSize = 12.sp
                )
            }
        }
        return
    }

    items(items = expenses, key = { it.id }) { expense ->
        val colors = Tabby.colors
        SwipeActionsRow(
            controller = swipeController,
            onClick = { onEdit(expense) },
            actions = listOf(
                SwipeAction(
                    label = "Edit",
                    icon = Icons.Filled.Edit,
                    background = colors.accent,
                    contentColor = colors.paper,
                    onClick = { onEdit(expense) }
                ),
                // Delete sits at the trailing EDGE, the native position on both
                // platforms, so the destructive action is where the thumb
                // expects it and is never the one revealed first.
                SwipeAction(
                    label = "Delete",
                    icon = Icons.Filled.Delete,
                    background = colors.negative,
                    contentColor = colors.ink,
                    onClick = { onDelete(expense) }
                )
            )
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 30dp with a 6dp pip, as on iOS; 26dp read as a bullet rather than
            // the badge the iOS row uses.
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(colors.accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(colors.accent, CircleShape)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = recentEntryTitle(expense),
                    color = colors.ink,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Two Texts, as on iOS: the category carries its own accent and
                // the timestamp stays subtleInk. Merging them into one string
                // painted the date in the category's colour, which made every
                // row look like it had a coloured date.
                //
                // The category takes `weight(fill = false)` so it ellipsises
                // when long while the date — measured first, as the unweighted
                // child — always renders whole. That is what stops the earlier
                // defect of both strings wrapping into 2-character fragments.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = expense.categoryName,
                        color = CategoryAccent.forCategory(expense.categoryName)
                            .color.copy(alpha = 0.84f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = DATE_FORMAT.format(expense.date.atZone(zone)),
                        color = colors.subtleInk,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Text(
                text = CurrencyFormat.full(expense.amount),
                color = colors.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                // Tabular figures so a column of amounts aligns, as iOS's
                // `.monospacedDigit()` does.
                style = moneyStyle(),
                // Never shrink the amount to make room for the icons: the
                // number is the point of the row.
                softWrap = false
            )
        }
        }
        // Inset to the text column, as on iOS: a divider running the full bleed
        // boxes every row in and makes the list look like a table.
        HorizontalDivider(
            color = colors.hairline,
            modifier = Modifier.padding(start = 42.dp)
        )
    }
}