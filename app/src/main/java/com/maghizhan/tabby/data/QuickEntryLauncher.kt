package com.maghizhan.tabby.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bridges "open quick entry" requests from outside the composition — the
 * launcher shortcut and the widget tap — into the running UI.
 *
 * Port of the iOS `QuickEntryLauncher`, with one deliberate change: an
 * observable value instead of an `onRequest` callback. A callback is
 * last-writer-wins, so an activity recreated on rotation overwrites the handler
 * the previous instance installed, and a request that arrives before the UI has
 * composed is dropped entirely — which on Android is the COMMON case, since the
 * shortcut cold-starts the app.
 *
 * The value is a CONSUMABLE request id, not a counter. A monotonic counter is
 * durable but never finishes: once it is non-zero it stays non-zero, so every
 * later recreation — a rotation, or the account-keyed tree being rebuilt —
 * observed the same non-zero value and reopened a sheet the user had already
 * used and dismissed. A request that has been acted on is cleared to null by
 * [consume], and only an id that is still pending can open the sheet.
 *
 * Null therefore means "nothing outstanding", which is also the correct initial
 * state; a request that arrives before sign-in simply stays pending until the
 * authenticated tree collects it.
 */
object QuickEntryLauncher {

    private val _requests = MutableStateFlow<Long?>(null)

    /** The outstanding request id, or null when there is none. */
    val requests: StateFlow<Long?> = _requests.asStateFlow()

    /**
     * Records a new request.
     *
     * The id comes from the caller (the activity, which knows whether an intent
     * is a genuinely new delivery or a retained one being replayed) so that an
     * already-handled delivery cannot be re-raised here.
     */
    fun request(id: Long) = _requests.update { id }

    /**
     * Marks [id] as handled.
     *
     * Compare-and-clear rather than a blind reset: a second request arriving
     * while the sheet was opening must not be swallowed by the first one's
     * acknowledgement.
     */
    fun consume(id: Long) = _requests.update { current -> if (current == id) null else current }

    /** Test seam: drops any outstanding request. */
    internal fun reset() = _requests.update { null }
}
