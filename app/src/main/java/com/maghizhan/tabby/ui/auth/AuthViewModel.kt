package com.maghizhan.tabby.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maghizhan.tabby.data.remote.AuthServicing
import com.maghizhan.tabby.data.remote.AuthSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Three-state root router.
 *
 * Session restoration is asynchronous, so a two-state (signed in / signed out)
 * router renders the login screen for the duration of the restore and then snaps
 * to Home — the login flash fixed on iOS. RESTORING is the honest initial state:
 * the app does not yet know, so it shows a splash and commits to neither branch.
 * Android ships this from day one rather than inheriting the bug.
 */
sealed interface AuthUiState {
    data object Restoring : AuthUiState
    data class Authenticated(val session: AuthSession) : AuthUiState
    data object SignedOut : AuthUiState
}

class AuthViewModel(
    private val authService: AuthServicing
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Restoring)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /**
     * Handle to the restore started in `init`, so tests (and any future caller
     * that must sequence after restore) can await it instead of polling.
     */
    val restoreJob: Job = viewModelScope.launch { restoreSession() }

    /**
     * Resolves RESTORING exactly once, to Authenticated when a session is
     * persisted and SignedOut when none is. Must always resolve — leaving the
     * router in Restoring would strand the user on the splash.
     *
     * The restore can still be in flight when another auth path resolves first —
     * most realistically an OAuth callback delivered on a cold launch, but also
     * an interactive sign-in or a sign-out. Because the restore reads the session
     * it captured *before* that happened, applying its stale result would clobber
     * the newer one and bounce a just-authenticated user back to the login
     * screen. So the result is committed only while the router is still
     * Restoring — i.e. only when nothing more recent has already decided.
     */
    suspend fun restoreSession() {
        val existing = authService.currentSession()
        if (_uiState.value !is AuthUiState.Restoring) return
        _uiState.value = existing?.let { AuthUiState.Authenticated(it) } ?: AuthUiState.SignedOut
    }

    suspend fun signInEmail(email: String, password: String) {
        _uiState.value = AuthUiState.Authenticated(authService.signInEmail(email, password))
    }

    suspend fun signUpEmail(email: String, password: String) {
        _uiState.value = AuthUiState.Authenticated(authService.signUpEmail(email, password))
    }

    suspend fun signInWithGoogle() {
        _uiState.value = AuthUiState.Authenticated(authService.signInWithGoogle())
    }

    /** Completes an OAuth round-trip from the custom-scheme callback URL. */
    suspend fun handleOAuthCallback(callbackUrl: String) {
        _uiState.value = AuthUiState.Authenticated(authService.completeOAuth(callbackUrl))
    }

    suspend fun signOut() {
        runCatching { authService.signOut() }
        _uiState.value = AuthUiState.SignedOut
    }
}
