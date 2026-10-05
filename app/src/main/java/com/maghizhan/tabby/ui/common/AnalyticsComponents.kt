package com.maghizhan.tabby.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.ui.format.CurrencyFormat
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyOrbit
import com.maghizhan.tabby.ui.theme.TabbyShapes
import java.math.BigDecimal

/** The card surface every analytics and list panel sits on. */
@Composable
fun TabbyCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = Tabby.colors
    Box(
        modifier = modifier
            .background(colors.surface.copy(alpha = 0.82f), TabbyShapes.card)
            .border(1.dp, colors.hairline, TabbyShapes.card)
            .padding(18.dp)
    ) { content() }
}

/** The dominant amount typography used across analytics headers. */
@Composable
fun AmountHeadline(title: String, amount: BigDecimal, modifier: Modifier = Modifier) {
    val colors = Tabby.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title.uppercase(),
            color = colors.subtleInk,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        Text(
            text = CurrencyFormat.full(amount),
            color = colors.ink,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/** Shown in place of a chart when the selected period has no spending. */
@Composable
fun EmptyAnalytics(modifier: Modifier = Modifier) {
    val colors = Tabby.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        TabbyOrbit(size = 34.dp, lineWidth = 2.dp)
        Spacer(Modifier.width(6.dp))
        Text(text = "No spending yet", color = colors.subtleInk, fontSize = 13.sp)
    }
}

/** A legend row: colour dot, category name, amount. */
@Composable
fun LegendRow(
    label: String,
    amount: BigDecimal?,
    dotColor: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = Tabby.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selected) colors.elevatedSurface else androidx.compose.ui.graphics.Color.Transparent,
                TabbyShapes.control
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, androidx.compose.foundation.shape.CircleShape)
        )
        Text(
            text = label,
            color = colors.ink,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            // Ellipsis rather than a hard clip, so a long category name reads as
            // shortened instead of looking like a different word ("Tran").
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (amount != null) {
            Text(
                text = CurrencyFormat.compact(amount),
                color = colors.subtleInk,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                // The amount never shrinks for the label; it is the data.
                softWrap = false
            )
        }
    }
}
