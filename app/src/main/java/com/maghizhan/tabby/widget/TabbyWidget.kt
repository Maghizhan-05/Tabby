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
import com.maghizhan.tabby.AppGraph
import com.maghizhan.tabby.analytics.AnalyticsEvent
import com.maghizhan.tabby.analytics.WidgetShape
import com.maghizhan.tabby.ui.format.CurrencyFormat
import com.maghizhan.tabby.ui.home.ringColor
import com.maghizhan.tabby.ui.theme.TabbyPalette

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
        setOf(SMALL_SIZE, SQUARE_SIZE, MEDIUM_SIZE)
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
            val config = WidgetConfig.from(currentState<Preferences>())
            WidgetBody(snapshot = snapshot, config = config, quickEntry = quickEntry)
        }
    }

    @Composable
    private fun WidgetBody(
        snapshot: WidgetSnapshot?,
        config: WidgetConfig,
        quickEntry: Intent
    ) {
        val size = LocalSize.current
        val mode = config.mode

        // Layout is chosen by SHAPE, not by matching a declared DpSize.
        //
        // SizeMode.Responsive buckets the cell to the nearest declared size,
        // but launcher grids report whatever their own cell maths produces —
        // a "square" placement is 140x132 on one launcher and 155x148 on
        // another, and neither equals SQUARE_SIZE. Comparing against a literal
        // would therefore pick the compact layout on one device and not the
        // next. The aspect ratio is the property that actually distinguishes
        // the layouts, so it is what the branch reads, with a width floor so a
        // tall-but-narrow cell is not mistaken for a roomy one.
        val aspect = if (size.height.value <= 0f) 1f else size.width / size.height
        val isCompactShape = aspect <= COMPACT_ASPECT_CEILING
        val isWide = !isCompactShape && size.width >= MEDIUM_SIZE.width

        // Ring-only when the cell is squarish: the breakdown needs horizontal
        // room the shape does not have, and the user asked for the iOS square
        // family — ring plus the day's figure, nothing else.
        val showBreakdown = config.showBreakdown && !isCompactShape
        val showRing = config.showRing

        // The ring grows with the cell, but is sized against the space ACTUALLY
        // left for it — not the whole cell. The widget's own chrome (padding top
        // and bottom, the mode header, the "+ Add spend" footer) consumes a
        // fixed slice of the height, and sizing a square ring off the full
        // height made it taller than the room it had: it overflowed, RemoteViews
        // clipped it, and the clipped result read as off-centre rather than too
        // big. Width loses only the horizontal padding.
        val availableHeight = (size.height.value - COMPACT_CHROME_HEIGHT).coerceAtLeast(0f)
        val availableWidth = (size.width.value - HORIZONTAL_CHROME).coerceAtLeast(0f)
        val ringSize = when {
            isCompactShape -> (minOf(availableWidth, availableHeight) * COMPACT_RING_FRACTION)
                .dp
                .coerceIn(48.dp, 124.dp)
            !isWide -> 44.dp
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

                // The body is the Quick Entry target.
                //
                // The footer button was spending ~22dp of a square cell's
                // height to say what a tap can say implicitly, and that height
                // came straight out of the ring. Putting the action on the body
                // keeps it while giving the art the room back.
                //
                // Applied to the BODY and not the root Column deliberately: the
                // header above keeps its own mode-cycling action, so the two
                // gestures stay distinct targets rather than the whole widget
                // becoming one button with no way to change period.
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight()
                        .clickable(actionStartActivity(quickEntry)),
                    // Centred outright in the compact shape. The ring is the
                    // only thing in the body there, and CenterStart left it
                    // hugging the leading edge with all the slack on one side.
                    // The wide layout keeps CenterStart so the ring still lines
                    // up with the header above its legend.
                    contentAlignment = if (isCompactShape) {
                        Alignment.Center
                    } else {
                        Alignment.CenterStart
                    }
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
                    else ->
                        RingWithLegend(
                            snapshot = snapshot,
                            mode = mode,
                            // Four legend rows beside a ring do not fit a 2x1;
                            // the compact shape shows none at all.
                            rows = if (isWide) WidgetSnapshot.MAXIMUM_SLICES else 1,
                            ringSize = ringSize,
                            showRing = showRing,
                            showBreakdown = showBreakdown,
                            centreOnly = isCompactShape
                        )
                }
                }

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
        ringSize: Dp,
        showRing: Boolean,
        showBreakdown: Boolean,
        centreOnly: Boolean
    ) {
        // The SELECTED mode's slices and total, not the month's. Hardcoding the
        // month drew a ring of the month's categories under a "TODAY" header
        // with the month's rupee figure in its centre — three different periods
        // in one widget.
        val slices = snapshot.slices(mode)
        if (slices.isEmpty() || !showRing) {
            // No ring to draw, or the user turned it off: the figure alone,
            // sized up since it is now the only thing in the cell.
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

        // Square cells centre the ring instead of pinning it to the leading
        // edge: with no legend beside it, a start-aligned ring leaves the cell
        // visibly lopsided.
        if (centreOnly) {
            Box(
                modifier = GlanceModifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                RingWithCentreLabel(
                    ring = ring,
                    ringSize = ringSize,
                    total = CurrencyFormat.compact(snapshot.total(mode))
                )
            }
            return
        }

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            if (ring != null) {
                RingWithCentreLabel(
                    ring = ring,
                    ringSize = ringSize,
                    total = CurrencyFormat.compact(snapshot.total(mode))
                )
                Spacer(modifier = GlanceModifier.width(10.dp))
            }
            if (showBreakdown) {
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
    }

    /**
     * The ring bitmap with the period total stacked over its centre.
     *
     * Glance has no canvas, so the label cannot be drawn INTO the bitmap; it is
     * layered in a Box instead. Keeping it as text rather than rasterising it
     * also means it still honours the user's font scale, which a baked-in label
     * would not.
     */
    @Composable
    private fun RingWithCentreLabel(ring: android.graphics.Bitmap?, ringSize: Dp, total: String) {
        if (ring == null) {
            TotalBody(total = total)
            return
        }
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
                text = total,
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(TabbyPalette.ink),
                    // Scales with the ring: a 12sp label centred in a 124dp
                    // square ring is lost, and the figure is the point of the
                    // compact layout.
                    fontSize = when {
                        ringSize >= 100.dp -> 20.sp
                        ringSize >= 76.dp -> 16.sp
                        ringSize >= 58.dp -> 12.sp
                        else -> 10.sp
                    },
                    fontWeight = FontWeight.Bold
                )
            )
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

        /**
         * The squarish family the iOS widget uses: ring plus the period's
         * figure, no breakdown.
         *
         * A declared bucket so Glance has a layout to resolve to, but NOT what
         * the layout branch compares against — see [COMPACT_ASPECT_CEILING].
         */
        val SQUARE_SIZE = DpSize(150.dp, 150.dp)
        val MEDIUM_SIZE = DpSize(250.dp, 110.dp)

        /**
         * At or below this width-to-height ratio the cell is treated as the
         * compact, ring-only shape.
         *
         * 1.25 rather than 1.0: launchers hand out cells that are nominally
         * square but a little wider than tall once padding is removed, and
         * demanding a true 1.0 would send most real "square" placements down
         * the wide path.
         */
        const val COMPACT_ASPECT_CEILING = 1.25f

        /**
         * How much of the compact body's shortest side the ring occupies.
         *
         * Applied to the space left AFTER chrome, not the raw cell, so it sits
         * near 1.0: the small remainder is breathing room, not a guess at how
         * much the header will take.
         */
        const val COMPACT_RING_FRACTION = 0.94f

        /**
         * Height consumed by the widget's own chrome in the compact layout:
         * 14dp padding top and bottom plus the ~16dp mode header. Quick Entry
         * now lives on the body tap, so no footer height is reserved.
         */
        const val COMPACT_CHROME_HEIGHT = 44f

        /** Horizontal padding, both sides. */
        const val HORIZONTAL_CHROME = 28f

        /** Past this the launcher has given us a tall cell worth a larger ring. */
        val TALL_HEIGHT = 180.dp

        /**
         * The Intent behind the widget body's Quick Entry target.
         *
         * Extracted so a test can exercise the SAME intent production uses: the
         * defect it guards against is one rendered widget's action being tapped
         * twice, which only reproduces if the test reuses one instance of this
         * intent rather than constructing a fresh one per tap.
         */
        internal fun quickEntryIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_QUICK_ENTRY, true)
                putExtra("com.maghizhan.tabby.extra.QUICK_ENTRY_SOURCE", "widget")
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
        AppGraph.from(context).analyticsTracker.track(
            AnalyticsEvent.WidgetPeriodCycled(WidgetShape.WIDE)
        )
    }
}

/** Registers [TabbyWidget] with the system. */
class TabbyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TabbyWidget()
}
