package com.maghizhan.tabby.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.maghizhan.tabby.MainActivity
import com.maghizhan.tabby.ui.format.CurrencyFormat
import com.maghizhan.tabby.ui.home.ringColor
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
        val size = LocalSize.current
        val isMedium = size.width >= MEDIUM_SIZE.width
        // The ring grows with the cell. A fixed 58dp ring looked deliberate in a
        // 2-row widget and lost in a 4-row one; the launcher lets the user
        // resize, so the art has to answer that.
        val ringSize = when {
            !isMedium -> 44.dp
            size.height >= TALL_HEIGHT -> 86.dp
            else -> 58.dp
        }
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

                // The body CENTRES in whatever height the launcher gave the
                // widget. Top-aligned with a single spacer above the footer, a
                // tall cell pinned the ring to the top third and left an empty
                // void beneath it — the widget looked broken rather than roomy.
                Box(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    contentAlignment = Alignment.CenterStart
                ) {
                when {
                    snapshot == null -> SignedOutBody()
                    // Per MODE, not per account: iOS decides its empty state
                    // from the slices of the period being shown, so a widget on
                    // TODAY says "No spending yet" on a quiet day even though
                    // the month has spending. Checking only "has this account
                    // ever spent" rendered a bare ₹0 instead.
                    snapshot.isEmpty(mode) -> EmptyBody()
                    // A ring in EVERY presentation, as on iOS, built from the
                    // selected period's own slices. Only CATEGORIES drew one
                    // before, so TODAY and THIS WEEK were a bare number on a
                    // widget whose whole point is the ring.
                    isMedium ->
                        // Medium: the ring beside its legend, as on the iOS
                        // widget and the in-app breakdown.
                        RingWithLegend(
                            snapshot = snapshot,
                            mode = mode,
                            rows = WidgetSnapshot.MAXIMUM_SLICES,
                            ringSize = ringSize
                        )
                    else ->
                        // Small: the ring with the leading category under it.
                        // Four legend rows beside a ring do not fit a 2x1.
                        RingWithLegend(
                            snapshot = snapshot,
                            mode = mode,
                            rows = 1,
                            ringSize = ringSize
                        )
                }
                }

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

    /**
     * The category ring beside (or above) its legend.
     *
     * The ring is a bitmap, not a drawn composable: Glance marshals its UI to
     * the launcher as a `RemoteViews` tree, which has no canvas primitive, so
     * the arc drawing the in-app chart uses is unavailable here. See
     * [WidgetRing].
     */
    @Composable
    private fun RingWithLegend(
        snapshot: WidgetSnapshot,
        mode: WidgetMode,
        rows: Int,
        ringSize: Dp
    ) {
        // The SELECTED mode's slices and total, not the month's. Hardcoding the
        // month drew a ring of the month's categories under a "TODAY" header
        // with the month's rupee figure in its centre — three different periods
        // in one widget.
        val slices = snapshot.slices(mode)
        if (slices.isEmpty()) {
            TotalBody(total = CurrencyFormat.compact(snapshot.total(mode)))
            return
        }

        val density = LocalContext.current.resources.displayMetrics.density
        val sizePx = (ringSize.value * density).toInt()
        val ring = WidgetRing.render(
            slices = slices,
            sizePx = sizePx,
            strokePx = sizePx * 0.19f
        )

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            if (ring != null) {
                // The ring's centre carries the period total, as on the iOS
                // widget — an unlabelled ring makes the user open the app to
                // learn what it adds up to. Glance has no canvas, so the label
                // is stacked over the bitmap in a Box rather than drawn into it;
                // keeping it as text means it still scales with the user's font
                // size, which a rasterised label would not.
                Box(
                    modifier = GlanceModifier.size(ringSize),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(ring),
                        contentDescription = "Spending by category",
                        modifier = GlanceModifier.size(ringSize)
                    )
                    Text(
                        text = CurrencyFormat.compact(snapshot.total(mode)),
                        maxLines = 1,
                        style = TextStyle(
                            color = ColorProvider(TabbyPalette.ink),
                            fontSize = if (ringSize >= 58.dp) 12.sp else 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                Spacer(modifier = GlanceModifier.width(10.dp))
            }
            Column(modifier = GlanceModifier.defaultWeight()) {
                slices.take(rows).forEachIndexed { index, slice ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        // The legend dot carries the slice's ring colour, so a
                        // row can be matched to its arc. Without it the ring is
                        // decorative and the legend unreadable.
                        Box(
                            modifier = GlanceModifier
                                .size(7.dp)
                                .cornerRadius(4.dp)
                                .background(ColorProvider(ringColor(index)))
                        ) {}
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        Text(
                            text = slice.category,
                            maxLines = 1,
                            style = TextStyle(
                                // Quieter than the amount, as on the iOS widget
                                // (white at 0.78 beside solid white): the number
                                // is what the row is for.
                                color = ColorProvider(TabbyPalette.subtleInk),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Spacer(modifier = GlanceModifier.defaultWeight())
                        Text(
                            text = CurrencyFormat.compact(slice.total),
                            maxLines = 1,
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
    }

    /**
     * Rendered when this account is signed in but the chosen period has no
     * spending — the iOS widget's "No spending yet", not a bare ₹0.
     */
    @Composable
    private fun EmptyBody() {
        Text(
            text = "No spending yet",
            style = TextStyle(
                color = ColorProvider(TabbyPalette.subtleInk),
                fontSize = 13.sp,
                // Glance's FontWeight has only Normal/Medium/Bold — there is no
                // SemiBold slot to match the iOS caption weight.
                fontWeight = FontWeight.Medium
            )
        )
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

        /** Past this the launcher has given us a tall cell worth a larger ring. */
        val TALL_HEIGHT = 180.dp

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
