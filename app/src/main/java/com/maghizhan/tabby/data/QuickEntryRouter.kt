package com.maghizhan.tabby.data

import android.content.Intent
import java.util.concurrent.atomic.AtomicLong

/**
 * Decides whether an incoming intent is a genuine new "open quick entry"
 * request, and mints the id that [QuickEntryLauncher] will carry.
 *
 * ## Why the id is minted HERE and not by the caller that sent the intent
 *
 * The widget's tap action is a `PendingIntent` built by Glance while the widget
 * content is being RENDERED, not when it is tapped. An id written into that
 * intent is therefore fixed for the lifetime of the render and every tap on the
 * same rendered widget delivered the SAME id — which the activity then
 * discarded as already handled, so the entry sheet opened on the first tap and
 * never again. Generating the id at click time is not possible either: Glance's
 * `ActionCallback` runs in the widget's background worker, and background
 * activity launches are blocked from API 29, so a callback cannot be the thing
 * that opens the sheet.
 *
 * The launcher shortcut has the same shape: its static `<intent>` carries no id
 * at all and cannot carry a fresh one.
 *
 * So the id is minted on arrival. What distinguishes a real tap from a replay is
 * not a value inside the intent but HOW the intent arrived — see [Delivery].
 */
internal object QuickEntryRouter {

    /**
     * How an intent reached the activity. This, rather than any extra, is what
     * tells a new request apart from the same intent being presented again.
     */
    enum class Delivery {
        /**
         * `onNewIntent`: the activity was already alive and the system handed it
         * a freshly delivered intent. Always a real tap — the platform never
         * replays a retained intent through this path.
         */
        NEW_INTENT,

        /**
         * `onCreate`: may be a cold start caused by this intent, or a recreated
         * activity re-reading the intent it has retained. [route] disambiguates
         * with `retainedIntentAlreadyConsumed`.
         */
        CREATE
    }

    /**
     * Returns the request id to raise, or null when [intent] carries no request
     * or is a replay that must be ignored.
     *
     * Three replay sources, three guards:
     *
     * 1. A configuration change rebuilds the activity, which re-reads the intent
     *    it retained — caught by [retainedIntentAlreadyConsumed].
     * 2. The user reopens the app from recents, where the system re-delivers the
     *    task's original launch intent with its extras intact — caught by
     *    `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, which the platform sets for
     *    exactly this case.
     *
     * A genuine repeated tap matches neither, which is the point: the second tap
     * on one rendered widget arrives as [Delivery.NEW_INTENT] and is honoured.
     *
     * The extra is deliberately NOT stripped from [intent]. Stripping it looks
     * like a third guard but is not one — the intent the activity receives is a
     * copy marshalled from the widget host, so mutating it cannot affect the
     * `PendingIntent` template behind the tap — while it WOULD make the outcome
     * depend on whether a caller happens to reuse one Intent instance, which is
     * precisely the distinction this defect turned on.
     *
     * @param retainedIntentAlreadyConsumed whether THIS activity's retained
     *   intent has already been acted on. Supplied from a retained
     *   [androidx.lifecycle.ViewModel], which is precisely the right lifetime: it
     *   survives a configuration change but dies with the process, so it cannot
     *   suppress a tap that relaunches a killed app. Saved instance state would
     *   be wrong here for that reason — it survives process death too, and so
     *   silently swallowed real taps.
     */
    fun route(
        intent: Intent?,
        delivery: Delivery,
        retainedIntentAlreadyConsumed: Boolean
    ): Long? {
        if (intent?.getBooleanExtra(MainActivityExtras.QUICK_ENTRY, false) != true) return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        if (delivery == Delivery.CREATE && retainedIntentAlreadyConsumed) return null

        return nextId()
    }

    /**
     * A strictly increasing id.
     *
     * Wall-clock milliseconds alone are not enough: two taps inside the same
     * millisecond would produce equal ids, and [QuickEntryLauncher] treats an
     * equal id as the same outstanding request. Clamping to "greater than the
     * last one issued" keeps ids unique under rapid tapping while staying
     * roughly time-ordered for readability in logs.
     */
    private fun nextId(now: Long = System.currentTimeMillis()): Long =
        lastIssuedId.updateAndGet { previous -> if (now > previous) now else previous + 1 }

    private val lastIssuedId = AtomicLong(0L)

    /** Test seam: forgets the last issued id. */
    internal fun resetIds() = lastIssuedId.set(0L)
}

/**
 * Extras understood by `MainActivity`, kept here so the widget, the shortcut
 * resource and [QuickEntryRouter] all name the same constant without the router
 * having to depend on the activity.
 */
object MainActivityExtras {
    /** Set by the launcher shortcut (`res/xml/shortcuts.xml`) and the widget. */
    const val QUICK_ENTRY = "com.maghizhan.tabby.extra.QUICK_ENTRY"
}
