package com.maghizhan.tabby.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maghizhan.tabby.data.remote.AuthServicing
import com.maghizhan.tabby.data.remote.AuthSession
import kotlinx.coroutines.CancellationException
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
 * Concurrency rule: every auth transition takes a monotonic id from
 * [intentCounter] when it STARTS, and may commit only if it is still the newest
 * *started* intent.
 *
 * Comparing against the newest started intent rather than the newest *committed*
 * one is the whole point. A newer sign-out that is still in flight — or that
 * failed outright — has committed nothing, so a committed-state comparison would
 * let an older sign-in land afterwards and silently re-authenticate a user who
 * deliberately signed out. Superseded work is discarded, never applied late.
 *
 * [CancellationException] is always rethrown rather than folded into a state.
 * Swallowing it would detach a cancelled coroutine from its scope: the ViewModel
 * could be cleared while an auth call kept running, which breaks structured
 * concurrency instead of merely being untidy.
 */
class AuthViewModel(
    private val authService: AuthServicing
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Restoring)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val authMutex = Mutex()

    /** Monotonic id handed to each auth intent as it starts; guarded by [authMutex]. */
    private var intentCounter: Long = 0L

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
        val next = try {
            val session = authService.currentSession()
            session?.let { AuthUiState.Authenticated(it) } ?: AuthUiState.SignedOut()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            AuthUiState.SignedOut(error = error.message ?: "Could not restore your session.")
        }
        commit(intent, next)
    }

    suspend fun signInEmail(email: String, password: String) =
        authenticate { authService.signInEmail(email, password) }

    suspend fun signUpEmail(email: String, password: String) =
        authenticate { authService.signUpEmail(email, password) }

    /**
     * Opens the Google consent page and returns.
     *
     * Deliberately does NOT authenticate and does NOT touch the router: the
     * browser round-trip only finishes when the custom-scheme callback arrives
     * at [handleOAuthCallback]. Claiming a session here reported "no session" on
     * a first-ever login, because the library call returns as soon as the
     * browser opens.
     */
    suspend fun beginGoogleSignIn() {
        authService.beginGoogleSignIn()
    }

    /**
     * Completes the OAuth round-trip from the callback URL. This is the call
     * that actually authenticates, by exchanging the PKCE code for a session.
     */
    suspend fun handleOAuthCallback(callbackUrl: String) =
        authenticate { authService.completeOAuth(callbackUrl) }

    suspend fun signOut() {
        val intent = beginIntent()
        try {
            authService.signOut()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Ignored on purpose: locally the user IS signed out even when
            // revoking the token over the network fails.
        }
        commit(intent, AuthUiState.SignedOut())
    }

    /**
     * Runs an interactive sign-in, committing only if it is still the newest
     * started intent. Rethrows so the caller can surface the failure, but leaves
     * the router untouched on failure — a wrong password at a re-auth prompt
     * must not evict an already authenticated user.
     */
    private suspend fun authenticate(block: suspend () -> AuthSession) {
        val intent = beginIntent()
        val session = block()
        commit(intent, AuthUiState.Authenticated(session))
    }

    private suspend fun beginIntent(): Long = authMutex.withLock { ++intentCounter }

    /**
     * Atomic compare-and-set against the newest *started* intent. Read and write
     * happen under the same lock, so the check-then-set cannot interleave.
     */
    private suspend fun commit(intent: Long, state: AuthUiState) {
        authMutex.withLock {
            // Strictly newer work has started, so this result is stale.
            if (intent != intentCounter) return
            _uiState.value = state
        }
    }
}
