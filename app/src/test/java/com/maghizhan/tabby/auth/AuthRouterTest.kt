package com.maghizhan.tabby.auth

import com.maghizhan.tabby.data.remote.AuthError
import com.maghizhan.tabby.data.remote.AuthServicing
import com.maghizhan.tabby.data.remote.AuthSession
import com.maghizhan.tabby.data.remote.InMemoryOAuthTransactionStore
import com.maghizhan.tabby.ui.auth.AuthUiState
import com.maghizhan.tabby.ui.auth.AuthViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Android counterpart of the iOS auth-router regression suite. Android ships
 * the three-state router from day one, so these tests exist to keep it that way:
 * a logged-in user must never see the login screen while the session restores,
 * and a late restore must never clobber a newer auth result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthRouterTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * Fake whose `currentSession()` suspends until the test releases it,
     * reproducing the real async restore window deterministically — the overlap
     * is forced, never raced.
     */
    private class GatedAuthService(
        private val restoredSession: AuthSession?,
        private val interactiveSession: AuthSession? = null
    ) : AuthServicing {
        override val isSupabaseConfigured = true
        override val isGoogleProviderConfigured = true

        val restoreStarted = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()
        var signOutCalled = false
            private set

        fun openGate() = gate.complete(Unit)

        override suspend fun currentSession(): AuthSession? {
            restoreStarted.complete(Unit)
            gate.await()
            return restoredSession
        }

        override suspend fun signInEmail(email: String, password: String): AuthSession =
            interactiveSession ?: throw AuthError.NotConfigured

        override suspend fun signUpEmail(email: String, password: String): AuthSession =
            interactiveSession ?: throw AuthError.NotConfigured

        override suspend fun beginGoogleSignIn() = Unit

        override suspend fun completeOAuth(callbackUrl: String): AuthSession =
            interactiveSession ?: throw AuthError.NotConfigured

        override suspend fun signOut() {
            signOutCalled = true
        }
    }

    private val sessionA = AuthSession("a1b2c3d4-0000-0000-0000-00000000feed", "me@example.com")
    private val sessionB = AuthSession("a1b2c3d4-0000-0000-0000-00000000cafe", "oauth@example.com")

    @Test
    fun `a persisted session never emits SignedOut before authenticating`() = runTest(dispatcher) {
        val fake = GatedAuthService(restoredSession = sessionA)
        val observed = mutableListOf<AuthUiState>()
        val viewModel = AuthViewModel(fake)
        val collector = launch { viewModel.uiState.toList(observed) }

        fake.restoreStarted.await()
        assertEquals(
            "router must stay Restoring while the session is still being restored",
            AuthUiState.Restoring,
            viewModel.uiState.value
        )

        fake.openGate()
        viewModel.restoreJob.join()

        assertEquals(AuthUiState.Authenticated(sessionA), viewModel.uiState.value)
        assertTrue(
            "router emitted SignedOut mid-restore - this is the login-flash bug: $observed",
            observed.none { it is AuthUiState.SignedOut }
        )
        assertEquals(
            listOf(AuthUiState.Restoring, AuthUiState.Authenticated(sessionA)),
            observed
        )
        collector.cancel()
    }

    @Test
    fun `no persisted session resolves to SignedOut rather than stranding the splash`() =
        runTest(dispatcher) {
            val fake = GatedAuthService(restoredSession = null)
            val viewModel = AuthViewModel(fake)

            fake.restoreStarted.await()
            assertEquals(AuthUiState.Restoring, viewModel.uiState.value)

            fake.openGate()
            viewModel.restoreJob.join()

            assertEquals(AuthUiState.SignedOut(), viewModel.uiState.value)
        }

    @Test
    fun `an OAuth callback during restore is not clobbered by the late restore result`() =
        runTest(dispatcher) {
            // The restore will answer "no persisted session" - the stale, losing answer.
            val fake = GatedAuthService(restoredSession = null, interactiveSession = sessionB)
            val observed = mutableListOf<AuthUiState>()
            // Armed: a callback is only honoured when it corresponds to a
            // sign-in this app began, so the test must model one.
            val viewModel = AuthViewModel(fake, InMemoryOAuthTransactionStore(pending = true))
            val collector = launch { viewModel.uiState.toList(observed) }

            fake.restoreStarted.await()
            viewModel.handleOAuthCallback("tabby://auth-callback?code=abc")
            assertEquals(AuthUiState.Authenticated(sessionB), viewModel.uiState.value)

            fake.openGate()
            viewModel.restoreJob.join()

            assertEquals(
                "late restore clobbered a newer OAuth session and returned the user to Login",
                AuthUiState.Authenticated(sessionB),
                viewModel.uiState.value
            )
            assertTrue(
                "router emitted SignedOut after OAuth authenticated: $observed",
                observed.none { it is AuthUiState.SignedOut }
            )
            collector.cancel()
        }

    @Test
    fun `an interactive sign-in during restore survives the late restore result`() =
        runTest(dispatcher) {
            val fake = GatedAuthService(restoredSession = null, interactiveSession = sessionA)
            val viewModel = AuthViewModel(fake)

            fake.restoreStarted.await()
            viewModel.signInEmail("me@example.com", "hunter2")
            assertEquals(AuthUiState.Authenticated(sessionA), viewModel.uiState.value)

            fake.openGate()
            viewModel.restoreJob.join()

            assertEquals(AuthUiState.Authenticated(sessionA), viewModel.uiState.value)
        }

    @Test
    fun `a sign-out during restore is not undone by the late restore result`() =
        runTest(dispatcher) {
            // The restore still holds the old session - it must not re-authenticate.
            val fake = GatedAuthService(restoredSession = sessionA)
            val viewModel = AuthViewModel(fake)

            fake.restoreStarted.await()
            viewModel.signOut()
            assertEquals(AuthUiState.SignedOut(), viewModel.uiState.value)

            fake.openGate()
            viewModel.restoreJob.join()

            assertEquals(
                "late restore re-authenticated a user who had already signed out",
                AuthUiState.SignedOut(),
                viewModel.uiState.value
            )
            assertTrue(fake.signOutCalled)
        }
}
