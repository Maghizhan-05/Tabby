package com.maghizhan.tabby.auth

import com.maghizhan.tabby.data.remote.AuthServicing
import com.maghizhan.tabby.data.remote.AuthSession
import com.maghizhan.tabby.data.remote.InMemoryOAuthTransactionStore
import com.maghizhan.tabby.ui.auth.AuthUiState
import com.maghizhan.tabby.ui.auth.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The OAuth callback filter is EXPORTED, so any app on the device can deliver a
 * structurally valid `com.maghizhan.tabby://auth-callback?...` intent.
 *
 * The defect: every such callback was processed, and a failed exchange committed
 * `SignedOut(error)` unconditionally — so any app could evict an authenticated
 * user to the login screen at will, simply by sending a callback with a bad code.
 * Callbacks are now bound to a sign-in this app actually began, and a failure
 * never discards a live session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OAuthCallbackBindingTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val session = AuthSession("a1b2c3d4-0000-0000-0000-00000000feed", "me@example.com")
    private val callbackUrl = "com.maghizhan.tabby://auth-callback?code=abc"

    /** Records whether the exchange was even attempted. */
    private inner class Service(
        private val restored: AuthSession?,
        private val oauthResult: Result<AuthSession>
    ) : AuthServicing {
        override val isSupabaseConfigured = true
        override val isGoogleProviderConfigured = true
        var exchangeAttempts = 0
            private set
        var consentOpened = false
            private set

        override suspend fun currentSession(): AuthSession? = restored
        override suspend fun signInEmail(email: String, password: String) = error("unused")
        override suspend fun signUpEmail(email: String, password: String) = error("unused")
        override suspend fun beginGoogleSignIn() { consentOpened = true }
        override suspend fun completeOAuth(callbackUrl: String): AuthSession {
            exchangeAttempts++
            return oauthResult.getOrThrow()
        }
        override suspend fun signOut() = Unit
    }

    @Test
    fun `an unsolicited callback is ignored and never reaches the token endpoint`() =
        runTest(dispatcher) {
            val service = Service(restored = null, oauthResult = Result.success(session))
            val viewModel = AuthViewModel(service, InMemoryOAuthTransactionStore(pending = false))
            viewModel.restoreJob.join()

            viewModel.handleOAuthCallback(callbackUrl)
            advanceUntilIdle()

            assertEquals(
                "an attacker-supplied code must not be exchanged",
                0,
                service.exchangeAttempts
            )
        }

    @Test
    fun `an unsolicited failing callback cannot evict an authenticated user`() =
        runTest(dispatcher) {
            val service = Service(
                restored = session,
                oauthResult = Result.failure(IllegalStateException("denied"))
            )
            val viewModel = AuthViewModel(service, InMemoryOAuthTransactionStore(pending = false))
            viewModel.restoreJob.join()
            assertTrue(viewModel.uiState.value is AuthUiState.Authenticated)

            viewModel.handleOAuthCallback(callbackUrl)
            advanceUntilIdle()

            assertFalse(
                "any app could force the router to Signed Out through the exported filter",
                viewModel.uiState.value is AuthUiState.SignedOut
            )
        }

    @Test
    fun `a failing callback during a real transaction keeps a live session`() =
        runTest(dispatcher) {
            // The user IS signed in and starts a re-auth / link flow that fails.
            // The failure belongs on the form, not in the router.
            val service = Service(
                restored = session,
                oauthResult = Result.failure(IllegalStateException("PKCE rejected"))
            )
            val viewModel = AuthViewModel(service, InMemoryOAuthTransactionStore(pending = true))
            viewModel.restoreJob.join()

            viewModel.handleOAuthCallback(callbackUrl)
            advanceUntilIdle()

            assertTrue(
                "a failed callback must not sign out an authenticated user",
                viewModel.uiState.value is AuthUiState.Authenticated
            )
            assertNotNull(
                "the user still needs to be told the attempt failed",
                viewModel.formState.value.notice
            )
        }

    @Test
    fun `a failing cold-start callback still resolves the splash screen`() = runTest(dispatcher) {
        // The cold-start case the original behaviour was written for: no session
        // yet, so committing nothing would strand the user on Restoring forever.
        val service = Service(
            restored = null,
            oauthResult = Result.failure(IllegalStateException("PKCE rejected"))
        )
        val viewModel = AuthViewModel(service, InMemoryOAuthTransactionStore(pending = true))
        viewModel.restoreJob.join()

        viewModel.handleOAuthCallback(callbackUrl)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("a failed callback must resolve the router", state is AuthUiState.SignedOut)
        assertNotNull((state as AuthUiState.SignedOut).error)
    }

    @Test
    fun `a solicited successful callback authenticates`() = runTest(dispatcher) {
        val service = Service(restored = null, oauthResult = Result.success(session))
        val store = InMemoryOAuthTransactionStore(pending = false)
        val viewModel = AuthViewModel(service, store)
        viewModel.restoreJob.join()

        // Opening the consent page is what arms the callback.
        viewModel.beginGoogleSignIn()
        assertTrue(service.consentOpened)
        assertTrue("the consent page must arm the callback", store.isPending())

        viewModel.handleOAuthCallback(callbackUrl)
        advanceUntilIdle()

        assertEquals(AuthUiState.Authenticated(session), viewModel.uiState.value)
    }

    @Test
    fun `a handled callback disarms the filter so a replay is ignored`() = runTest(dispatcher) {
        val service = Service(restored = null, oauthResult = Result.success(session))
        val store = InMemoryOAuthTransactionStore(pending = true)
        val viewModel = AuthViewModel(service, store)
        viewModel.restoreJob.join()

        viewModel.handleOAuthCallback(callbackUrl)
        advanceUntilIdle()
        assertEquals(1, service.exchangeAttempts)
        assertFalse("a completed transaction must disarm", store.isPending())

        // A replayed delivery (retained intent, recreation) must not re-exchange
        // an already-consumed code.
        viewModel.handleOAuthCallback(callbackUrl)
        advanceUntilIdle()
        assertEquals("a consumed code must not be exchanged twice", 1, service.exchangeAttempts)
    }

    @Test
    fun `a consent page that fails to open does not leave the filter armed`() =
        runTest(dispatcher) {
            val failing = object : AuthServicing {
                override val isSupabaseConfigured = true
                override val isGoogleProviderConfigured = true
                override suspend fun currentSession(): AuthSession? = null
                override suspend fun signInEmail(email: String, password: String) = error("unused")
                override suspend fun signUpEmail(email: String, password: String) = error("unused")
                override suspend fun beginGoogleSignIn() =
                    throw IllegalStateException("provider unavailable")
                override suspend fun completeOAuth(callbackUrl: String) = error("unused")
                override suspend fun signOut() = Unit
            }
            val store = InMemoryOAuthTransactionStore(pending = false)
            val viewModel = AuthViewModel(failing, store)
            viewModel.restoreJob.join()

            runCatching { viewModel.beginGoogleSignIn() }

            assertFalse(
                "no browser round-trip can return, so the filter must not stay armed",
                store.isPending()
            )
        }
}
