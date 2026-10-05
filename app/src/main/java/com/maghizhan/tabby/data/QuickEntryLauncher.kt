package com.maghizhan.tabby.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bridges "open quick entry" requests from outside the composition — the
 * launcher shortcut and the widget tap — into the running UI.
 *
 * Port of the iOS `QuickEntryLauncher`, with one deliberate change: a
 * `StateFlow` counter instead of an `onRequest` callback. A callback is
 * last-writer-wins, so an activity recreated on rotation overwrites the handler
 * the previous instance installed, and a request that arrives before the UI has
 * composed is dropped entirely — which on Android is the COMMON case, since the
 * shortcut cold-starts the app. A counter is observable and durable: whoever
 * collects next sees that a request happened.
 *
 * A counter rather than a boolean because two consecutive requests must be two
 * openings; `true` → `true` is not a state change and the second tap would do
 * nothing.
 */
object QuickEntryLauncher {

    private val _requests = MutableStateFlow(0)

    /** Increments on each request. Collectors open the sheet on every change. */
    val requests: StateFlow<Int> = _requests.asStateFlow()

    fun requestQuickEntry() = _requests.update { it + 1 }
}
