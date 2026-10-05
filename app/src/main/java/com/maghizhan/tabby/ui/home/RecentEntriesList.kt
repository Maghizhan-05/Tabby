package com.maghizhan.tabby.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.ui.format.CurrencyFormat
import com.maghizhan.tabby.ui.theme.CategoryAccent
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyOrbit
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Row title: the note when present, else "<Category> expense" — the iOS rule. */
fun recentEntryTitle(expense: ExpenseEntity): String =
    expense.note?.takeIf { it.isNotBlank() } ?: "${expense.categoryName} expense"

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

/**
 * Recent spending, deliberately quiet so the analytics visual stays primary.
 *
 * Edit and Delete are explicit icon buttons rather than a swipe gesture. Compose
 * has no 1:1 `.swipeActions`, and a swipe-to-dismiss would make Delete the
 * easiest action on a destructive operation against financial records; visible
 * buttons are also reachable by a screen reader and by switch access, which a
 * swipe-only affordance is not.
 */
fun LazyListScope.recentEntries(
    expenses: List<ExpenseEntity>,
    onEdit: (ExpenseEntity) -> Unit,
    onDelete: (ExpenseEntity) -> Unit,
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEdit(expense) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(colors.accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(colors.accent, CircleShape)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recentEntryTitle(expense),
                    color = colors.ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                // One line, ellipsised. Two Texts in a Row each wrapped
                // independently and produced a stack of 2-character fragments
                // on a narrow screen; a single string with maxLines = 1 cannot.
                Text(
                    text = "${expense.categoryName} · ${DATE_FORMAT.format(expense.date.atZone(zone))}",
                    color = CategoryAccent.forCategory(expense.categoryName)
                        .color.copy(alpha = 0.84f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = CurrencyFormat.full(expense.amount),
                color = colors.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                // Never shrink the amount to make room for the icons: the
                // number is the point of the row.
                softWrap = false
            )

            IconButton(onClick = { onEdit(expense) }) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit ${recentEntryTitle(expense)}",
                    tint = colors.accent
                )
            }
            IconButton(onClick = { onDelete(expense) }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete ${recentEntryTitle(expense)}",
                    tint = colors.subtleInk
                )
            }
        }
        HorizontalDivider(color = colors.hairline)
    }
}