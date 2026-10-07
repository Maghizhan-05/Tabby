package com.maghizhan.tabby

import androidx.lifecycle.ViewModel

/**
 * Per-activity, per-process state for the quick-entry intent path.
 *
 * A [ViewModel] purely for its LIFETIME, not for any logic: it survives a
 * configuration change but dies with the process. That is exactly the window in
 * which an activity's retained intent can be re-presented, and so exactly the
 * window in which "this intent already opened the sheet" is true.
 *
 * Saved instance state is the wrong tool here even though it is what one reaches
 * for: it also survives process death, so a widget tap that relaunched a killed
 * app restored the flag and suppressed a genuinely new request.
 */
internal class QuickEntryActivityState : ViewModel() {

    /**
     * Set once the activity's current intent has raised a quick-entry request,
     * so a recreation re-reading that same retained intent does not raise it
     * again.
     */
    var retainedIntentConsumed: Boolean = false
}
