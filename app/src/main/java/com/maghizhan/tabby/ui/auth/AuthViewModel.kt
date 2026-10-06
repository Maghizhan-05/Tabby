package com.maghizhan.tabby.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maghizhan.tabby.data.remote.AuthServicing
import com.maghizhan.tabby.data.remote.AuthSession
import com.maghizhan.tabby.data.remote.InMemoryOAuthTransactionStore
import com.maghizhan.tabby.data.remote.OAuthTransactionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

/** Whether the email/password form signs in or creates an account. */
enum class AuthMode { SIGN_IN, CREATE_ACCOUNT }

/**
 * Turns an auth failure into a message that is safe to put on screen.
 *
 * The Supabase client's exception messages embed the whole failed request —
 * URL, query string and the `Authorization: Bearer <key>` header. Rendering
 * `error.message` directly puts that on the user's display, into any
 * screenshot they send to support, and into a bug report. It is also useless
 * to the user, who cannot act on an HTTP dump.
 *
 * So: recognised failures get a written sentence, and anything unrecognised
 * gets a generic one rather than a raw passthrough.
 */
internal fun authNoticeFor(error: Throwable, mode: AuthMode): String {
    val raw = (error.message ?: "").lowercase()
    return when {
        "invalid_credentials" in raw || "invalid login credentials" in raw ->
            "That email and password don't match an account."
        "user_already_exists" in raw || "already registered" in raw ->
            "An account with that email already exists. Try signing in."
        "weak_password" in raw -> "Pick a longer password — at least 6 characters."
        "email_address_invalid" in raw || "unable to validate email" in raw ->
            "That email address isn't valid."
        "over_email_send_rate_limit" in raw || "rate limit" in raw ->
            "Too many attempts. Wait a moment and try again."
        "email_not_confirmed" in raw -> "Confirm your email address, then sign in."
        error is java.io.IOException ||
            "unresolvedaddress" in raw ||
            "failed to connect" in raw ||
            "timeout" in raw -> "Can't reach Tabby. Check your connection."
        mode == AuthMode.CREATE_ACCOUNT -> "Could not create your account. Try again."
        else -> "Could not sign in. Try again."
    }
}

/**
 * The login form's own fields and validation, separate from [AuthUiState].
 *
 * Deliberately NOT folded into the router state. The router answers "which of
 * the three screens is showing"; these are the text a user is mid-way through
 * typing. Merging them would make every keystroke a router transition and the
 * three-state contract — and its regression tests — would be re-litigated by
 * form edits.
 */
data class LoginFormState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val mode: AuthMode = AuthMode.SIGN_IN,
    val isBusy: Boolean = false,
    val notice: String? = null
) {
    /**
     * Minimally valid: text either side of an "@" and a dot inside the domain.
     * Deliberately lenient — the server is the source of truth; this only stops
     * obvious typos from submitting.
     */
    val isEmailValid: Boolean
        get() {
            val trimmed = email.trim()
            val at = trimmed.indexOf('@')
            if (at <= 0) return false
            val domain = trimmed.substring(at + 1)
            return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
        }

    /** Supabase's default minimum. */
    val isPasswordValid: Boolean get() = password.length >= 6

    val passwordsMatch: Boolean get() = password == confirmPassword

    /** Shown only once the user has typed a confirmation, so it never nags. */
    val confirmPasswordError: String?
        get() = if (mode == AuthMode.CREATE_ACCOUNT &&
            confirmPassword.isNotEmpty() &&
            !passwordsMatch
        ) {
            "Passwords don't match."
        } else {
            null
        }

    val canSubmit: Boolean
        get() {
            if (isBusy || !isEmailValid || !isPasswordValid) return false
            return mode != AuthMode.CREATE_ACCOUNT || (passwordsMatch && confirmPassword.isNotEmpty())
        }

    val primaryCtaTitle: String
        get() = when {
            isBusy && mode == AuthMode.CREATE_ACCOUNT -> "Creating account"
            isBusy -> "Signing in"
            mode == AuthMode.CREATE_ACCOUNT -> "Create Account"
            else -> "Enter Tabby"
        }
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
    private val authService: AuthServicing,
    /**
     * Records whether an OAuth sign-in this app started is awaiting its
     * callback. See [handleOAuthCallback]: without it, any app on the device can
     * force the router to Signed Out through the exported callback filter.
     */
    private val oauthTransactions: OAuthTransactionStore = InMemoryOAuthTransactionStore()
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Restoring)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _formState = MutableStateFlow(LoginFormState())
    val formState: StateFlow<LoginFormState> = _formState.asStateFlow()

    val isSupabaseConfigured: Boolean get() = authService.isSupabaseConfigured
    val isGoogleProviderConfigured: Boolean get() = authService.isGoogleProviderConfigured

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
            // Sanitised, not raw: a restore failure carries the same request
            // dump (URL + bearer token) as any other client error.
            AuthUiState.SignedOut(error = authNoticeFor(error, AuthMode.SIGN_IN))
        }
        commit(intent, next)
    }

    suspend fun signInEmail(email: String, password: String) =
        authenticate { authService.signInEmail(email, password) }

    suspend fun signUpEmail(email: String, password: String) =
        authenticate { authService.signUpEmail(email, password) }

    // MARK: - Login form

    fun onEmailChanged(value: String) =
        _formState.update { it.copy(email = value, notice = null) }

    fun onPasswordChanged(value: String) =
        _formState.update { it.copy(password = value, notice = null) }

    fun onConfirmPasswordChanged(value: String) =
        _formState.update { it.copy(confirmPassword = value, notice = null) }

    /** Switches between sign-in and create-account, clearing transient state. */
    fun toggleMode() = _formState.update {
        val next = if (it.mode == AuthMode.SIGN_IN) AuthMode.CREATE_ACCOUNT else AuthMode.SIGN_IN
        it.copy(
            mode = next,
            notice = null,
            confirmPassword = if (next == AuthMode.SIGN_IN) "" else it.confirmPassword
        )
    }

    /**
     * Runs the form's primary action for the current mode.
     *
     * Failures land in the form's own `notice`, not in the router: a wrong
     * password typed by an already-signed-in user at a re-auth prompt must not
     * evict them to the login screen. The router is only moved by a SUCCESSFUL
     * authentication, which [authenticate] commits.
     */
    fun submitPrimary() {
        val form = _formState.value
        if (!form.canSubmit) return
        _formState.update { it.copy(isBusy = true, notice = null) }

        viewModelScope.launch {
            try {
                when (form.mode) {
                    AuthMode.SIGN_IN -> signInEmail(form.email.trim(), form.password)
                    AuthMode.CREATE_ACCOUNT -> signUpEmail(form.email.trim(), form.password)
                }
                // Credentials are cleared on success so they are not retained in
                // memory after they stop being needed.
                _formState.value = LoginFormState()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _formState.update {
                    it.copy(isBusy = false, notice = authNoticeFor(error, form.mode))
                }
            }
        }
    }

    /** Opens the Google consent page; the session arrives via the callback. */
    fun startGoogleSignIn() {
        _formState.update { it.copy(isBusy = true, notice = null) }
        viewModelScope.launch {
            try {
                beginGoogleSignIn()
                // Busy is cleared immediately: the browser is now in charge, and
                // leaving the form spinning would strand it if the user simply
                // backs out of the consent page and never returns a callback.
                _formState.update { it.copy(isBusy = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _formState.update {
                    it.copy(isBusy = false, notice = authNoticeFor(error, AuthMode.SIGN_IN))
                }
            }
        }
    }

    /** Signs out without blocking the caller, clearing the form too. */
    fun requestSignOut() {
        viewModelScope.launch {
            signOut()
            _formState.value = LoginFormState()
        }
    }

    /**
     * Opens the Google consent page and returns.
     *
     * Deliberately does NOT authenticate and does NOT touch the router: the
     * browser round-trip only finishes when the custom-scheme callback arrives
     * at [handleOAuthCallback]. Claiming a session here reported "no session" on
     * a first-ever login, because the library call returns as soon as the
     * browser opens.
     *
     * The pending transaction is recorded BEFORE the browser opens, and only on
     * success would be too late: the callback can reach a freshly started
     * process, so the record must already be durable when the user consents.
     */
    suspend fun beginGoogleSignIn() {
        oauthTransactions.begin()
        try {
            authService.beginGoogleSignIn()
        } catch (error: Throwable) {
            // The consent page never opened, so no callback can be expected;
            // leaving the transaction pending would re-arm the exported filter.
            oauthTransactions.clear()
            throw error
        }
    }

    /**
     * Completes the OAuth round-trip from the callback URL. This is the call
     * that actually authenticates, by exchanging the PKCE code for a session.
     *
     * Two rules, both of which exist because the callback intent filter is
     * EXPORTED — any app on the device can deliver a structurally valid callback:
     *
     * 1. A callback is ignored unless it corresponds to a sign-in this app
     *    actually started. Without that binding, another app could disrupt the
     *    router at will by sending a callback with a bad code.
     *
     * 2. A FAILED exchange must not evict an already authenticated user. It
     *    commits `SignedOut(error)` only when the router is not authenticated —
     *    which is the cold-start case this behaviour was written for: there the
     *    callback claims the newest intent, so the restore that would have
     *    produced a state has already been superseded, and committing nothing
     *    would strand the user on the splash screen permanently. When a session
     *    is live there is a meaningful state to keep, so the failure is reported
     *    on the form instead.
     */
    suspend fun handleOAuthCallback(callbackUrl: String) {
        if (!oauthTransactions.isPending()) return

        val intent = beginIntent()
        val next = try {
            AuthUiState.Authenticated(authService.completeOAuth(callbackUrl))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val notice = authNoticeFor(error, AuthMode.SIGN_IN)
            oauthTransactions.clear()
            if (_uiState.value is AuthUiState.Authenticated) {
                _formState.update { it.copy(isBusy = false, notice = notice) }
                return
            }
            AuthUiState.SignedOut(error = notice)
        }
        oauthTransactions.clear()
        commit(intent, next)
    }

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

    companion object {
        /**
         * Builds instances for an activity's [androidx.lifecycle.ViewModelStore].
         *
         * A factory, not a `by lazy` field on the activity: auth work runs in
         * `viewModelScope`, and an instance owned by the activity instance is
         * neither retained across a configuration change nor ever cleared. On
         * rotation that produced a SECOND router doing a second restore while the
         * first kept running against a state nobody observed — stale auth work
         * and lost UI results. Obtained from the store, one instance spans the
         * activity's whole lifetime and is cleared exactly once.
         */
        fun factory(
            authService: AuthServicing,
            oauthTransactions: OAuthTransactionStore
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { AuthViewModel(authService, oauthTransactions) }
        }
    }

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
