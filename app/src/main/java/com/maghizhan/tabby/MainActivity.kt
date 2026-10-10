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
import com.maghizhan.tabby.data.MainActivityExtras
import com.maghizhan.tabby.data.QuickEntryLauncher
import com.maghizhan.tabby.data.QuickEntryRouter
import com.maghizhan.tabby.data.remote.OAuthCallback
import com.maghizhan.tabby.ui.auth.AuthUiState
import com.maghizhan.tabby.ui.auth.AuthViewModel
import com.maghizhan.tabby.ui.nav.RootNav
import com.maghizhan.tabby.ui.theme.TabbyTheme
import com.maghizhan.tabby.analytics.AnalyticsEvent
import kotlinx.coroutines.launch

/**
 * Single activity, `singleTask` so the OAuth callback is delivered to this
 * instance via [onNewIntent] rather than launching a second copy that would
 * restart session restoration mid-exchange.
 */
class MainActivity : ComponentActivity() {

    /** The application object graph; see [AppGraph] for what is wired to what. */
    private val graph: AppGraph by lazy { AppGraph.from(applicationContext) }

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
     * Whether this activity's RETAINED intent has already opened quick entry.
     *
     * Held in a retained [androidx.lifecycle.ViewModel] rather than saved
     * instance state. Both survive a rotation, but saved state ALSO survives
     * process death — and a widget tap that relaunches a killed process is a
     * genuinely new request arriving on a restored activity, which the saved
     * flag wrongly suppressed. A retained view model has exactly the lifetime
     * this guard needs: cleared with the process, kept across configuration
     * changes.
     */
    private val quickEntryState: QuickEntryActivityState by viewModels()

    /**
     * The OAuth callback URL already exchanged.
     *
     * Still saved instance state: unlike a quick-entry tap, an authorisation
     * code is single-use, so re-presenting it after process death must stay
     * suppressed.
     */
    private var handledCallbackUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handledCallbackUrl = savedInstanceState?.getString(STATE_HANDLED_CALLBACK)

        lifecycleScope.launch {
            graph.analyticsTracker.track(AnalyticsEvent.AppOpened(coldStart = !processOpened))
            processOpened = true
        }

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
        handleQuickEntry(intent, QuickEntryRouter.Delivery.CREATE)

        // The cold-start path: the app was launched BY the callback, so the
        // intent is already on the activity and onNewIntent will never fire for
        // it. Missing this is why a first-ever OAuth login appeared to hang.
        handleCallback(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Only the callback URL: the quick-entry guard deliberately lives in a
        // retained view model, so that a tap which relaunches a killed process
        // is still treated as a new request. See [quickEntryState].
        handledCallbackUrl?.let { outState.putString(STATE_HANDLED_CALLBACK, it) }
    }

    /** The warm path: the app was already running when the browser returned. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the activity's own intent current so a later getIntent() sees it.
        setIntent(intent)
        handleCallback(intent)
        handleQuickEntry(intent, QuickEntryRouter.Delivery.NEW_INTENT)
    }

    /**
     * Routes a quick-entry request from the launcher shortcut or the widget.
     *
     * Matched on an explicit extra rather than a browsable URI: this path never
     * needs to be reachable from a web redirect, and adding a second browsable
     * host to the exported OAuth filter would widen the app's attack surface
     * for no benefit.
     *
     * The decision of whether this delivery is a real request, and the id it
     * carries, both belong to [QuickEntryRouter] — see its documentation for why
     * the id cannot come from inside the intent (the widget's PendingIntent is
     * built at RENDER time, so every tap on one rendered widget would reuse the
     * same id and only the first would open the sheet).
     */
    private fun handleQuickEntry(intent: Intent?, delivery: QuickEntryRouter.Delivery) {
        val requestId = QuickEntryRouter.route(
            intent = intent,
            delivery = delivery,
            retainedIntentAlreadyConsumed = quickEntryState.retainedIntentConsumed
        ) ?: return

        quickEntryState.retainedIntentConsumed = true
        QuickEntryLauncher.request(requestId)
        if (intent?.getStringExtra(EXTRA_QUICK_ENTRY_SOURCE) == QUICK_ENTRY_SOURCE_WIDGET) {
            lifecycleScope.launch {
                graph.analyticsTracker.track(
                    AnalyticsEvent.WidgetTapped(com.maghizhan.tabby.analytics.WidgetShape.WIDE)
                )
            }
            intent.removeExtra(EXTRA_QUICK_ENTRY_SOURCE)
        }
    }

    companion object {
        /** Set by the launcher shortcut and the widget's tap action. */
        const val EXTRA_QUICK_ENTRY = MainActivityExtras.QUICK_ENTRY
        const val EXTRA_QUICK_ENTRY_SOURCE = "com.maghizhan.tabby.extra.QUICK_ENTRY_SOURCE"
        const val QUICK_ENTRY_SOURCE_WIDGET = "widget"

        private const val STATE_HANDLED_CALLBACK = "handledCallbackUrl"

        @Volatile
        private var processOpened = false
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
            runCatching {
                authViewModel.handleOAuthCallback(url)
                if (graph.deletionRequests.isPending() &&
                    authViewModel.uiState.value is AuthUiState.Authenticated
                ) {
                    graph.accountDeletion.deleteImmediately()
                    authViewModel.signOut()
                }
            }
        }
    }
}
