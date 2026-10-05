package com.maghizhan.tabby

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maghizhan.tabby.data.remote.OAuthCallback
import com.maghizhan.tabby.data.remote.SupabaseAuthService
import com.maghizhan.tabby.ui.auth.AuthUiState
import com.maghizhan.tabby.ui.auth.AuthViewModel
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
        AuthViewModel(SupabaseAuthService())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TabbyTheme {
                val state by authViewModel.uiState.collectAsState()
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Checkpoint 2 is the data/auth layer; the real screens
                        // arrive in the UI checkpoint. This renders the router's
                        // resolved state so the three states are observable on
                        // a device rather than only in tests.
                        Text(
                            text = when (state) {
                                AuthUiState.Restoring -> "Tabby\nRestoring your session…"
                                is AuthUiState.Authenticated -> "Tabby\nSigned in"
                                is AuthUiState.SignedOut ->
                                    (state as AuthUiState.SignedOut).error
                                        ?.let { "Tabby\nSigned out — $it" }
                                        ?: "Tabby\nSigned out"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }

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
            // Errors (denied consent, malformed callback) resolve the router to
            // SignedOut with a reason instead of stranding the splash screen.
            runCatching { authViewModel.handleOAuthCallback(url) }
        }
    }
}
