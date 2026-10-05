package com.maghizhan.tabby.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
// The Intent overload lives in the appwidget artifact; the one in
// glance.action takes a ComponentName or a Class and so cannot carry extras.
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.maghizhan.tabby.MainActivity
import com.maghizhan.tabby.data.local.TabbyDatabase
import com.maghizhan.tabby.ui.format.CurrencyFormat
import com.maghizhan.tabby.ui.home.PeriodUnit
import com.maghizhan.tabby.ui.home.SpendingAnalytics
import com.maghizhan.tabby.ui.theme.TabbyPalette
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/**
 * Home-screen widget: today's total and a tap target that logs a spend.
 *
 * Reads the local Room database directly rather than the backend. The widget
 * must render instantly and offline, and the local database is already the
 * app's source of truth — the sync coordinator keeps it current.
 *
 * Deliberately NOT owner-filtered by a session lookup: the widget process has
 * no session, and blocking on one would leave the widget blank whenever the
 * token needed refreshing. Instead it reads whichever owner the database
 * already has rows for, which on a single-account device is the signed-in user.
 */
class TabbyWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = loadTodayTotal(context)
        // The intent is built here, where a real Context exists; Glance's
        // composition runs in a restricted environment without LocalContext.
        val quickEntry = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_QUICK_ENTRY, true)
        }
        provideContent { WidgetBody(today, quickEntry) }
    }

    @Composable
    private fun WidgetBody(todayTotal: BigDecimal, quickEntry: Intent) {
        GlanceTheme {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(TabbyPalette.paper))
                    .cornerRadius(18.dp)
                    .padding(14.dp)
                    .clickable(actionStartActivity(quickEntry)),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.Start
            ) {
                Text(
                    text = "TODAY",
                    style = TextStyle(
                        color = ColorProvider(TabbyPalette.subtleInk),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = CurrencyFormat.compact(todayTotal),
                    style = TextStyle(
                        color = ColorProvider(TabbyPalette.ink),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Row(modifier = GlanceModifier.padding(top = 6.dp)) {
                    Text(
                        text = "+ Add spend",
                        style = TextStyle(
                            color = ColorProvider(TabbyPalette.accentBright),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
    }

    private suspend fun loadTodayTotal(context: Context): BigDecimal {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val (start, end) = SpendingAnalytics.intervalOf(
            unit = PeriodUnit.DAY,
            instant = now,
            zone = zone
        )
        // A one-shot query, not a Flow: a widget renders once per update and
        // holding a subscription open would keep the database alive in a
        // process the system is free to kill at any moment.
        val rows = TabbyDatabase.get(context).expenseDao().allVisible()
        return SpendingAnalytics.total(SpendingAnalytics.inRange(rows, start, end))
    }
}

/** Registers [TabbyWidget] with the system. */
class TabbyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TabbyWidget()
}
