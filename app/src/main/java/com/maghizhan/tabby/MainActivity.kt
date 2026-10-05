package com.maghizhan.tabby

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
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

    /**
     * Shared between the composition and the intent handlers.
     *
     * Held here rather than created inside `setContent` because the callback can
     * arrive before or after composition, and both paths must reach the SAME
     * router instance — a second instance would exchange the code and then
     * update a state nobody is observing.
     */
    private val authViewModel: AuthViewModel by lazy {
        AuthViewModel(graph.authService)
    }

    /** The application object graph; see [AppGraph] for what is wired to what. */
    private val graph: AppGraph by lazy { AppGraph(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sync runs when a session becomes available, which is the first moment
        // it can: before this, local rows may exist that the backend has never
        // seen. Collected for the activity's lifetime so a sign-in arriving
        // later (OAuth callback, restored session) also triggers a run.
        lifecycleScope.launch {
            authViewModel.uiState.collect { state ->
                if (state is AuthUiState.Authenticated) {
                    graph.syncScheduler.onAuthenticated()
                }
            }
        }

        // Default categories are seeded before the UI needs them. Off the main
        // thread, since this touches the database.
        lifecycleScope.launch { graph.seedDefaultCategories() }

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
     */
    private fun handleQuickEntry(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_QUICK_ENTRY, false) != true) return
        // Consumed immediately: the intent is retained by the activity, and a
        // sticky extra would reopen the sheet on every later rotation.
        intent.removeExtra(EXTRA_QUICK_ENTRY)
        QuickEntryLauncher.requestQuickEntry()
    }

    companion object {
        /** Set by the launcher shortcut and the widget's tap action. */
        const val EXTRA_QUICK_ENTRY = "com.maghizhan.tabby.extra.QUICK_ENTRY"
    }

    /**
     * Completes the PKCE exchange if (and only if) [intent] carries one of our
     * callbacks.
     *
     * Validation lives in [OAuthCallback.parse] because this intent filter is
     * exported: any app on the device can send us a VIEW intent, so the scheme,
     * host and boundary are checked before a code is read. Anything that is not
     * ours is ignored silently rather than failing the launch.
     */
    private fun handleCallback(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        if (OAuthCallback.parse(url) is OAuthCallback.Result.NotACallback) return

        lifecycleScope.launch {
            // handleOAuthCallback commits SignedOut(error) itself on failure, so
            // the router always resolves; this catch only stops a callback
            // failure from taking the activity down.
            runCatching { authViewModel.handleOAuthCallback(url) }
        }
    }
}
