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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * Not signed in. [error] is set when the decision was forced by a failure
     * rather than by a genuinely absent session, so the login screen can explain
     * itself instead of appearing for no visible reason.
     */
    data class SignedOut(val error: String? = null) : AuthUiState
}

/**
 * Owns the router state.
 *
 * Concurrency rule: every auth transition runs under [authMutex] and stamps a
 * monotonic [intentCounter]. Auth operations overlap in practice — an OAuth
 * callback can arrive mid-restore, and a user can hit sign-out while a slow
 * sign-in is still in flight — and without ordering, whichever network call
 * happens to finish last wins. That is how a signed-out user gets silently
 * re-authenticated by a stale sign-in. A result is applied only if no newer
 * intent started after it, so the user's most recent action always decides.
 */
class AuthViewModel(
    private val authService: AuthServicing
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Restoring)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val authMutex = Mutex()

    /** Monotonic id for each started auth intent; guarded by [authMutex]. */
    private var intentCounter: Long = 0L

    /** The newest intent that has *committed* a state; guarded by [authMutex]. */
    private var latestCommittedIntent: Long = 0L

    /**
     * Handle to the restore started in `init`, so tests (and any future caller
     * that must sequence after restore) can await it instead of polling.
     */
    val restoreJob: Job = viewModelScope.launch { restoreSession() }

    /**
     * Resolves RESTORING exactly once, to Authenticated when a session is
     * persisted and SignedOut when none is.
     *
     * Must ALWAYS resolve. An exception from [AuthServicing.currentSession] —
     * expired refresh token, no network, corrupt stored session — previously
     * propagated out and left the router in Restoring forever, stranding the
     * user on the splash screen with no way forward. A failed restore is treated
     * as "not signed in, and here is why": the user can still sign in manually.
     */
    suspend fun restoreSession() {
        val intent = beginIntent()
        val outcome = runCatching { authService.currentSession() }
        val next = outcome.fold(
            onSuccess = { session ->
                session?.let { AuthUiState.Authenticated(it) } ?: AuthUiState.SignedOut()
            },
            onFailure = { error ->
                AuthUiState.SignedOut(error = error.message ?: "Could not restore your session.")
            }
        )
        commit(intent, next)
    }

    suspend fun signInEmail(email: String, password: String) =
        authenticate { authService.signInEmail(email, password) }

    suspend fun signUpEmail(email: String, password: String) =
        authenticate { authService.signUpEmail(email, password) }

    suspend fun signInWithGoogle() = authenticate { authService.signInWithGoogle() }

    /** Completes an OAuth round-trip from the custom-scheme callback URL. */
    suspend fun handleOAuthCallback(callbackUrl: String) =
        authenticate { authService.completeOAuth(callbackUrl) }

    suspend fun signOut() {
        val intent = beginIntent()
        runCatching { authService.signOut() }
        // Committed regardless: locally the user IS signed out even if the
        // network call to revoke the token failed.
        commit(intent, AuthUiState.SignedOut())
    }

    /**
     * Runs an interactive sign-in, committing only if it is still the newest
     * intent. Rethrows so the caller can surface the failure, but leaves the
     * router untouched on failure — a failed sign-in must not kick an already
     * authenticated user out.
     */
    private suspend fun authenticate(block: suspend () -> AuthSession) {
        val intent = beginIntent()
        val session = block()
        commit(intent, AuthUiState.Authenticated(session))
    }

    private suspend fun beginIntent(): Long = authMutex.withLock { ++intentCounter }

    /**
     * Atomic compare-and-set: applies [state] only when no later intent has
     * already committed. Read and write happen under the same lock, so the
     * check-then-set cannot interleave.
     */
    private suspend fun commit(intent: Long, state: AuthUiState) {
        authMutex.withLock {
            if (intent < latestCommittedIntent) return
            latestCommittedIntent = intent
            _uiState.value = state
        }
    }
}
