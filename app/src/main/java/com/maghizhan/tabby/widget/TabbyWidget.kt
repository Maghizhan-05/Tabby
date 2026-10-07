package com.maghizhan.tabby.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
// The Intent overload lives in the appwidget artifact; the one in
// glance.action takes a ComponentName or a Class and so cannot carry extras.
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.maghizhan.tabby.MainActivity
import com.maghizhan.tabby.ui.format.CurrencyFormat
import com.maghizhan.tabby.ui.theme.TabbyPalette

/** Per-widget persisted mode, so each placed instance keeps its own choice. */
internal val WIDGET_MODE_KEY = stringPreferencesKey("tabby_widget_mode")

/**
 * Home-screen widget: an account's spending total, cycled through day / week /
 * month / category presentations, plus a Quick Entry button.
 *
 * ## Where the data comes from
 *
 * A sanitised [WidgetSnapshot] written by the app, NOT the Room cache. The cache
 * is shared by every account that has signed in on this device and the widget
 * process has no session to scope a query with, so reading it unscoped totalled
 * foreign accounts' rows and kept showing them after sign-out. The snapshot is
 * owner-scoped when written and cleared on sign-out, and an absent snapshot
 * renders the signed-out state rather than falling back to any cached rows.
 *
 * ## Why the mode lives in Glance state
 *
 * Android has no equivalent of the iOS widget configuration intent, so the mode
 * is cycled by tapping the header. It is persisted per widget id in the Glance
 * preferences state so a host restart, a process death or a reboot does not reset
 * every placed widget back to TODAY.
 */
class TabbyWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<Preferences> =
        PreferencesGlanceStateDefinition

    /**
     * Responsive rather than a single layout: a 2x1 widget has no room for a
     * category breakdown, and rendering the medium layout into it clipped the
     * rows. Glance picks the largest declared size that fits and the composition
     * reads [LocalSize] to decide what to show.
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SMALL_SIZE, MEDIUM_SIZE)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = PreferencesWidgetSnapshotStore(context).read()

        // Built here, where a real Context exists; Glance's composition runs in
        // a restricted environment without LocalContext.
        //
        // Deliberately carries NO request id. Glance builds this into a
        // PendingIntent while the content is RENDERED, not when it is tapped, so
        // an id written here is frozen for the lifetime of the render and every
        // tap delivers the same one — which the activity then discarded as
        // already handled, so Quick Entry opened once per render and never
        // again. The id is minted on arrival by QuickEntryRouter instead.
        val quickEntry = quickEntryIntent(context)

        provideContent {
            val mode = WidgetMode.fromName(currentState(WIDGET_MODE_KEY))
            WidgetBody(snapshot = snapshot, mode = mode, quickEntry = quickEntry)
        }
    }

    @Composable
    private fun WidgetBody(
        snapshot: WidgetSnapshot?,
        mode: WidgetMode,
        quickEntry: Intent
    ) {
        val isMedium = LocalSize.current.width >= MEDIUM_SIZE.width
        GlanceTheme {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(TabbyPalette.paper))
                    .cornerRadius(18.dp)
                    .padding(14.dp),
                verticalAlignment = Alignment.Vertical.Top,
                horizontalAlignment = Alignment.Horizontal.Start
            ) {
                // The header cycles the mode. Scoped to the header alone: making
                // the WHOLE widget launch Quick Entry left no way to change mode
                // and no way to tap the widget without opening the entry sheet.
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .clickable(actionRunCallback<CycleWidgetModeAction>()),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        text = mode.title,
                        style = TextStyle(
                            color = ColorProvider(TabbyPalette.subtleInk),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    Text(
                        text = "⟳",
                        style = TextStyle(
                            color = ColorProvider(TabbyPalette.subtleInk),
                            fontSize = 12.sp
                        )
                    )
                }

                when {
                    snapshot == null -> SignedOutBody()
                    mode == WidgetMode.CATEGORIES && isMedium ->
                        CategoryBody(snapshot = snapshot, rows = WidgetSnapshot.MAXIMUM_SLICES)
                    mode == WidgetMode.CATEGORIES ->
                        // Small: one leading category, since four rows do not fit.
                        CategoryBody(snapshot = snapshot, rows = 1)
                    else -> TotalBody(total = CurrencyFormat.compact(snapshot.total(mode)))
                }

                Spacer(modifier = GlanceModifier.defaultWeight())

                // A SEPARATE action from the header, so cycling the mode and
                // logging a spend are distinct targets.
                Text(
                    text = "+ Add spend",
                    style = TextStyle(
                        color = ColorProvider(TabbyPalette.accentBright),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = GlanceModifier
                        .padding(top = 6.dp)
                        .clickable(actionStartActivity(quickEntry))
                )
            }
        }
    }

    @Composable
    private fun TotalBody(total: String) {
        Text(
            text = total,
            style = TextStyle(
                color = ColorProvider(TabbyPalette.ink),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }

    @Composable
    private fun CategoryBody(snapshot: WidgetSnapshot, rows: Int) {
        if (snapshot.categorySlices.isEmpty()) {
            TotalBody(total = CurrencyFormat.compact(snapshot.total(WidgetMode.MONTH)))
            return
        }
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            for (slice in snapshot.categorySlices.take(rows)) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        text = slice.category,
                        style = TextStyle(
                            color = ColorProvider(TabbyPalette.ink),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    Text(
                        text = CurrencyFormat.compact(slice.total),
                        style = TextStyle(
                            color = ColorProvider(TabbyPalette.ink),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }

    /**
     * Rendered when no snapshot exists: signed out, or never signed in.
     *
     * Deliberately NOT a fallback to cached rows. "Nothing to show" is the
     * correct, safe answer; the alternative is the home screen displaying the
     * previous user's spending to whoever is holding the phone.
     */
    @Composable
    private fun SignedOutBody() {
        Text(
            text = "Sign in to see your spending",
            style = TextStyle(
                color = ColorProvider(TabbyPalette.subtleInk),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }

    internal companion object {
        val SMALL_SIZE = DpSize(160.dp, 80.dp)
        val MEDIUM_SIZE = DpSize(250.dp, 110.dp)

        /**
         * The Intent behind the widget's "+ Add spend" target.
         *
         * Extracted so a test can exercise the SAME intent production uses: the
         * defect it guards against is one rendered widget's action being tapped
         * twice, which only reproduces if the test reuses one instance of this
         * intent rather than constructing a fresh one per tap.
         */
        internal fun quickEntryIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_QUICK_ENTRY, true)
            }
    }
}

/**
 * Advances this widget instance's mode and redraws it.
 *
 * Written through [updateAppWidgetState] rather than held in the composition:
 * the composition is discarded between updates, so a remembered value would
 * reset to TODAY on the next host refresh.
 */
class CycleWidgetModeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters
    ) {
        updateAppWidgetState(context, glanceId) { preferences ->
            val current = WidgetMode.fromName(preferences[WIDGET_MODE_KEY])
            preferences[WIDGET_MODE_KEY] = current.next().name
        }
        TabbyWidget().update(context, glanceId)
    }
}

/** Registers [TabbyWidget] with the system. */
class TabbyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TabbyWidget()
}
