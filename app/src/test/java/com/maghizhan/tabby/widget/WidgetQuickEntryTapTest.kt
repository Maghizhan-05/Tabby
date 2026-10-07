package com.maghizhan.tabby.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.maghizhan.tabby.MainActivity
import com.maghizhan.tabby.data.MainActivityExtras
import com.maghizhan.tabby.data.QuickEntryLauncher
import com.maghizhan.tabby.data.QuickEntryRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tapping the widget's "+ Add spend" opens Quick Entry EVERY time, not once per
 * widget render.
 *
 * The defect: the request id was written into the Intent inside
 * `provideGlance`, which runs when the widget content is RENDERED, not when it
 * is tapped. Glance turns that one Intent into one `PendingIntent`, so every tap
 * on a given render delivered an identical id, and the activity — which ignores
 * an id it has already raised — discarded all but the first. Quick Entry opened
 * once per render and then appeared dead until the host happened to redraw.
 *
 * `QuickEntryLauncherTest` could not catch this: it calls
 * [QuickEntryLauncher.request] with ids it invents itself, so it tested the
 * launcher's bookkeeping while assuming away the very thing that was broken —
 * where the id comes from. These tests go through the real widget Intent and the
 * real routing the activity performs, and reuse ONE Intent instance across taps
 * exactly as a single render does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetQuickEntryTapTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        QuickEntryLauncher.reset()
        QuickEntryRouter.resetIds()
    }

    /**
     * The regression test for the reported defect.
     *
     * One render, two taps, and the second must open the sheet just like the
     * first. The Intent is built once and reused, which is what makes this a
     * real reproduction rather than two independent launches.
     */
    @Test
    fun `two consecutive taps on one rendered widget both open quick entry`() {
        // One render of the widget produces one Intent, reused by every tap.
        val renderedIntent = TabbyWidget.quickEntryIntent(context)

        val firstId = tap(renderedIntent)
        assertNotNull("the first tap must open quick entry", firstId)
        assertEquals(firstId, QuickEntryLauncher.requests.value)

        // The UI opens the sheet and acknowledges the request, as RootNav does.
        QuickEntryLauncher.consume(firstId!!)
        assertNull(QuickEntryLauncher.requests.value)

        val secondId = tap(renderedIntent)
        assertNotNull(
            "the second tap on the same rendered widget must open quick entry too",
            secondId
        )
        assertNotEquals(
            "a reused request id is what the activity discards as already handled",
            firstId,
            secondId
        )
        assertEquals(
            "the second tap did not reach the UI",
            secondId,
            QuickEntryLauncher.requests.value
        )
    }

    /**
     * Rapid tapping, where every tap can land inside the same millisecond.
     *
     * Wall-clock time alone is not a sufficient id for this reason, so ten taps
     * must still be ten distinct openings.
     */
    @Test
    fun `rapid taps all open quick entry`() {
        val renderedIntent = TabbyWidget.quickEntryIntent(context)

        val ids = (1..10).map { tap(renderedIntent) }

        assertTrue("every tap must open quick entry", ids.all { it != null })
        assertEquals("ids collided, so a tap was swallowed", 10, ids.toSet().size)
    }

    /** A tap is honoured when the app was not already running. */
    @Test
    fun `a cold-start tap opens quick entry`() {
        val id = QuickEntryRouter.route(
            intent = TabbyWidget.quickEntryIntent(context),
            delivery = QuickEntryRouter.Delivery.CREATE,
            retainedIntentAlreadyConsumed = false
        )

        assertNotNull("a launch caused by the tap must open quick entry", id)
    }

    /**
     * The original bug this routing exists to prevent, still prevented: a
     * configuration change re-reads the activity's retained intent and must not
     * reopen a sheet the user has already used.
     */
    @Test
    fun `recreating the activity does not reopen quick entry`() {
        val renderedIntent = TabbyWidget.quickEntryIntent(context)

        assertNotNull(tap(renderedIntent))

        val afterRecreation = QuickEntryRouter.route(
            intent = renderedIntent,
            delivery = QuickEntryRouter.Delivery.CREATE,
            // The retained view model survived the configuration change.
            retainedIntentAlreadyConsumed = true
        )

        assertNull("a rotation must not raise the request again", afterRecreation)
    }

    /**
     * Reopening the app from recents re-delivers the task's original launch
     * intent with its extras intact; the platform marks it, and it must not be
     * mistaken for a tap.
     */
    @Test
    fun `relaunching from recents does not open quick entry`() {
        val fromHistory = TabbyWidget.quickEntryIntent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        }

        val id = QuickEntryRouter.route(
            intent = fromHistory,
            delivery = QuickEntryRouter.Delivery.CREATE,
            // The process was killed, so nothing is retained.
            retainedIntentAlreadyConsumed = false
        )

        assertNull("a recents relaunch is not a tap", id)
    }

    /** An ordinary launch carries no request. */
    @Test
    fun `a launch without the extra opens nothing`() {
        val plain = Intent(context, MainActivity::class.java)

        assertNull(
            QuickEntryRouter.route(
                intent = plain,
                delivery = QuickEntryRouter.Delivery.CREATE,
                retainedIntentAlreadyConsumed = false
            )
        )
    }

    /** The widget's intent carries the extra the activity matches on. */
    @Test
    fun `the widget intent targets the quick-entry path`() {
        val intent = TabbyWidget.quickEntryIntent(context)

        assertTrue(intent.getBooleanExtra(MainActivityExtras.QUICK_ENTRY, false))
    }

    /**
     * One tap on an already-running app, routed the way `onNewIntent` routes it,
     * and raised to the UI the way the activity raises it.
     */
    private fun tap(renderedIntent: Intent): Long? =
        QuickEntryRouter.route(
            intent = renderedIntent,
            delivery = QuickEntryRouter.Delivery.NEW_INTENT,
            retainedIntentAlreadyConsumed = true
        )?.also { QuickEntryLauncher.request(it) }
}
