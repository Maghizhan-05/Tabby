package com.maghizhan.tabby

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.maghizhan.tabby.data.QuickEntryLauncher
import com.maghizhan.tabby.data.remote.OAuthCallback
import com.maghizhan.tabby.ui.auth.AuthUiState
import com.maghizhan.tabby.ui.auth.AuthViewModel
import com.maghizhan.tabby.ui.nav.RootNav
import com.maghizhan.tabby.ui.theme.TabbyTheme
import kotlinx.coroutines.launch

/**
 * Single activity, `singleTask` so the OAuth callback is delivered to this
 * instance via [onNewIntent] rather than launching a second copy that would
 * restart session restoration mid-exchange.
 */
class MainActivity : ComponentActivity() {

    /** The application object graph; see [AppGraph] for what is wired to what. */
    private val graph: AppGraph by lazy { AppGraph(applicationContext) }

    /**
     * Retained in the activity's [androidx.lifecycle.ViewModelStore].
     *
     * NOT constructed directly with `by lazy`: auth work runs in
     * `viewModelScope`, and an instance owned by the activity instance survives
     * neither a configuration change nor `onCleared`. On rotation that produced
     * a SECOND router performing a second restore while the first kept running
     * against a state nobody observed — stale auth work and lost UI results.
     * Obtained from the store, one instance spans the activity's whole lifetime
     * (including rotations) and is cleared exactly once.
     *
     * Still held here rather than inside `setContent` because the callback can
     * arrive before or after composition and both paths must reach the SAME
     * router; the store is what makes that true across recreation too.
     */
    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModel.factory(graph.authService, graph.oauthTransactions)
    }

    /**
     * The last quick-entry request this activity has already raised.
     *
     * Saved instance state, because the triggering intent is RETAINED by the
     * activity: without it, a recreation re-read the same intent and raised the
     * request again, reopening a sheet the user had already used.
     */
    private var lastQuickEntryRequestId: Long = 0L

    /** Same problem, same fix, for the OAuth callback URL. */
    private var handledCallbackUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lastQuickEntryRequestId = savedInstanceState?.getLong(STATE_QUICK_ENTRY_ID) ?: 0L
        handledCallbackUrl = savedInstanceState?.getString(STATE_HANDLED_CALLBACK)

        // Sync runs when a session becomes available, which is the first moment
        // it can: before this, local rows may exist that the backend has never
        // seen. Collected for the activity's lifetime so a sign-in arriving
        // later (OAuth callback, restored session) also triggers a run.
        //
        // The widget's owner is published from the same place, so a sign-out or
        // an account change clears or replaces its snapshot rather than leaving
        // the previous account's totals on the home screen.
        // StateFlow already conflates equal consecutive values, so no
        // distinctUntilChanged is needed (and applying one has no effect).
        lifecycleScope.launch {
            authViewModel.uiState.collect { state ->
                when (state) {
                    is AuthUiState.Authenticated -> {
                        graph.widgetUpdater.setActiveOwner(state.session.userId)
                        // Defaults are seeded before the first sync of a session
                        // so a pull cannot race seeding; seeding itself is now
                        // marker-based, so ordering is belt-and-braces rather
                        // than the correctness guarantee.
                        graph.seedDefaultCategories()
                        graph.syncScheduler.onAuthenticated()
                    }
                    is AuthUiState.SignedOut -> graph.widgetUpdater.setActiveOwner(null)
                    AuthUiState.Restoring -> Unit
                }
            }
        }

        // Defaults are also seeded before any UI needs them, for the signed-out
        // and first-run cases. Idempotent via the seed marker, so running it
        // here and on authentication cannot double-seed.
        lifecycleScope.launch { graph.seedDefaultCategories() }

        // Returning to the foreground pulls remote changes. Without this, an
        // edit or delete made on another device stayed invisible until the next
        // authentication or relaunch. `repeatOnLifecycle` fires on every
        // STARTED transition and the scheduler's own mutex plus its signed-out
        // check keep runs serialized and skipped when there is no session.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.syncScheduler.onForegrounded()
            }
        }

        setContent {
            TabbyTheme {
                Surface(
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxSize()
                ) {
                    RootNav(
                        graph = graph,
                        authViewModel = authViewModel,
                        syncScheduler = graph.syncScheduler
                    )
                }
            }
        }

        // A launcher shortcut or widget tap. Handled alongside the OAuth
        // callback because both arrive as intents on this same activity.
        handleQuickEntry(intent)

        // The cold-start path: the app was launched BY the callback, so the
        // intent is already on the activity and onNewIntent will never fire for
        // it. Missing this is why a first-ever OAuth login appeared to hang.
        handleCallback(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Both are "already handled" markers for data the activity's retained
        // intent will present again after recreation.
        outState.putLong(STATE_QUICK_ENTRY_ID, lastQuickEntryRequestId)
        handledCallbackUrl?.let { outState.putString(STATE_HANDLED_CALLBACK, it) }
    }

    /** The warm path: the app was already running when the browser returned. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the activity's own intent current so a later getIntent() sees it.
        setIntent(intent)
        handleCallback(intent)
        handleQuickEntry(intent)
    }

    /**
     * Routes a quick-entry request from the launcher shortcut or the widget.
     *
     * Matched on an explicit extra rather than a browsable URI: this path never
     * needs to be reachable from a web redirect, and adding a second browsable
     * host to the exported OAuth filter would widen the app's attack surface
     * for no benefit.
     *
     * A request carries an id, and an id already raised is ignored. Removing the
     * extra is not sufficient on its own: the activity can be recreated from the
     * retained intent, and the pending request itself used to be a monotonic
     * counter that stayed non-zero forever, so the sheet reopened on rotation.
     */
    private fun handleQuickEntry(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_QUICK_ENTRY, false) != true) return

        // Shortcuts carry no id of their own; the arrival time is the id.
        val requestId = intent.getLongExtra(EXTRA_QUICK_ENTRY_REQUEST_ID, 0L)
            .takeIf { it != 0L } ?: System.currentTimeMillis()

        if (requestId == lastQuickEntryRequestId) return
        lastQuickEntryRequestId = requestId

        intent.removeExtra(EXTRA_QUICK_ENTRY)
        intent.removeExtra(EXTRA_QUICK_ENTRY_REQUEST_ID)
        QuickEntryLauncher.request(requestId)
    }

    companion object {
        /** Set by the launcher shortcut and the widget's tap action. */
        const val EXTRA_QUICK_ENTRY = "com.maghizhan.tabby.extra.QUICK_ENTRY"

        /** Distinguishes a new request from a retained intent being replayed. */
        const val EXTRA_QUICK_ENTRY_REQUEST_ID = "com.maghizhan.tabby.extra.QUICK_ENTRY_ID"

        private const val STATE_QUICK_ENTRY_ID = "lastQuickEntryRequestId"
        private const val STATE_HANDLED_CALLBACK = "handledCallbackUrl"
    }

    /**
     * Completes the PKCE exchange if (and only if) [intent] carries one of our
     * callbacks that has not already been handled.
     *
     * Validation lives in [OAuthCallback.parse] because this intent filter is
     * exported: any app on the device can send us a VIEW intent, so the scheme,
     * host and boundary are checked before a code is read. Anything that is not
     * ours is ignored silently rather than failing the launch. The view model
     * additionally refuses any callback that does not correspond to a sign-in
     * this app started, so an unsolicited one cannot move the router.
     *
     * The handled URL is remembered and persisted across recreation: the intent
     * is retained by the activity, so without this a rotation re-ran the
     * exchange with an already-consumed code, which fails and used to be
     * reported to the user as a sign-in failure.
     */
    private fun handleCallback(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        if (OAuthCallback.parse(url) is OAuthCallback.Result.NotACallback) return
        if (url == handledCallbackUrl) return
        handledCallbackUrl = url

        // Cleared so a later getIntent() (or a recreation before state is
        // restored) cannot see the callback data again.
        intent.data = null

        lifecycleScope.launch {
            // handleOAuthCallback resolves the router itself where that is the
            // right outcome, so this catch only stops a callback failure from
            // taking the activity down.
            runCatching { authViewModel.handleOAuthCallback(url) }
        }
    }
}
