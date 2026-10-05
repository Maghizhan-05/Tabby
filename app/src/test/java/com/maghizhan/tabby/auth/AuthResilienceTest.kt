package com.maghizhan.tabby.auth

import com.maghizhan.tabby.data.remote.AuthServicing
import com.maghizhan.tabby.data.remote.AuthSession
import com.maghizhan.tabby.ui.auth.AuthUiState
import com.maghizhan.tabby.ui.auth.AuthViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
 * Two failure modes a three-state router introduces, both of which strand or
 * mislead the user if unhandled:
 *
 * 1. A restore that THROWS (expired refresh token, no network, corrupt stored
 *    session) must still resolve. Left in Restoring, the user sits on a splash
 *    screen forever with no way to sign in.
 *
 * 2. Overlapping auth operations must resolve by the user's most recent intent,
 *    not by whichever network call finishes last. Otherwise a slow sign-in
 *    completing after a sign-out silently signs the user back in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthResilienceTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val session = AuthSession("a1b2c3d4-0000-0000-0000-00000000feed", "me@example.com")

    /** Restore fails; every other operation is unused. */
    private class ThrowingRestoreService(private val error: Throwable) : AuthServicing {
        override val isSupabaseConfigured = true
        override val isGoogleProviderConfigured = true
        override suspend fun currentSession(): AuthSession? = throw error
        override suspend fun signInEmail(email: String, password: String) = error("unused")
        override suspend fun signUpEmail(email: String, password: String) = error("unused")
        override suspend fun beginGoogleSignIn() = Unit
        override suspend fun completeOAuth(callbackUrl: String) = error("unused")
        override suspend fun signOut() = Unit
    }

    @Test
    fun `a restore that throws resolves to SignedOut instead of stranding on the splash`() =
        runTest(dispatcher) {
            val viewModel = AuthViewModel(ThrowingRestoreService(IllegalStateException("token expired")))

            viewModel.restoreJob.join()

            val state = viewModel.uiState.value
            assertTrue("router must leave Restoring even when restore fails", state is AuthUiState.SignedOut)
            // The message is sanitised rather than passed through: a real client
            // exception carries the request URL and bearer token, which must not
            // reach the screen. The contract is "explained, not leaked".
            val error = (state as AuthUiState.SignedOut).error
            assertNotNull(error)
            assertFalse("raw exception text must not reach the UI", error!!.contains("token expired"))
        }

    @Test
    fun `a failed restore explains itself so the login screen is not unexplained`() =
        runTest(dispatcher) {
            val viewModel = AuthViewModel(ThrowingRestoreService(RuntimeException("offline")))
            viewModel.restoreJob.join()
            assertNotNull((viewModel.uiState.value as AuthUiState.SignedOut).error)
        }

    /** Sign-in hangs until released, so sign-out can deterministically overtake it. */
    private class SlowSignInService(private val session: AuthSession) : AuthServicing {
        override val isSupabaseConfigured = true
        override val isGoogleProviderConfigured = true

        val signInStarted = CompletableDeferred<Unit>()
        private val signInGate = CompletableDeferred<Unit>()
        var signOutCalled = false
            private set

        fun releaseSignIn() = signInGate.complete(Unit)

        override suspend fun currentSession(): AuthSession? = null

        override suspend fun signInEmail(email: String, password: String): AuthSession {
            signInStarted.complete(Unit)
            signInGate.await()
            return session
        }

        override suspend fun signUpEmail(email: String, password: String) = error("unused")
        override suspend fun beginGoogleSignIn() = Unit
        override suspend fun completeOAuth(callbackUrl: String) = error("unused")

        override suspend fun signOut() {
            signOutCalled = true
        }
    }

    @Test
    fun `a sign-in that completes after sign-out does not re-authenticate the user`() =
        runTest(dispatcher) {
            // The security-relevant ordering bug: the user deliberately signed
            // out, so a stale in-flight sign-in must not undo that.
            val fake = SlowSignInService(session)
            val viewModel = AuthViewModel(fake)
            viewModel.restoreJob.join()

            val signIn = launch { viewModel.signInEmail("me@example.com", "pw") }
            fake.signInStarted.await()

            viewModel.signOut()
            assertTrue(fake.signOutCalled)
            assertTrue(viewModel.uiState.value is AuthUiState.SignedOut)

            // Now let the older sign-in finish.
            fake.releaseSignIn()
            signIn.join()
            advanceUntilIdle()

            assertTrue(
                "a stale sign-in silently re-authenticated a signed-out user",
                viewModel.uiState.value is AuthUiState.SignedOut
            )
        }

    @Test
    fun `sign-out is applied even when revoking the token fails`() = runTest(dispatcher) {
        // Locally the user IS signed out; a network failure revoking the token
        // must not leave them looking authenticated.
        val failing = object : AuthServicing {
            override val isSupabaseConfigured = true
            override val isGoogleProviderConfigured = true
            override suspend fun currentSession() = session
            override suspend fun signInEmail(email: String, password: String) = error("unused")
            override suspend fun signUpEmail(email: String, password: String) = error("unused")
            override suspend fun beginGoogleSignIn() = Unit
            override suspend fun completeOAuth(callbackUrl: String) = error("unused")
            override suspend fun signOut() = throw IllegalStateException("network down")
        }

        val viewModel = AuthViewModel(failing)
        viewModel.restoreJob.join()
        assertTrue(viewModel.uiState.value is AuthUiState.Authenticated)

        viewModel.signOut()

        assertTrue(viewModel.uiState.value is AuthUiState.SignedOut)
    }

    @Test
    fun `a failed sign-in leaves an authenticated user signed in`() = runTest(dispatcher) {
        // A wrong password on a re-auth prompt must not evict the live session.
        val failing = object : AuthServicing {
            override val isSupabaseConfigured = true
            override val isGoogleProviderConfigured = true
            override suspend fun currentSession() = session
            override suspend fun signInEmail(email: String, password: String): AuthSession =
                throw IllegalStateException("bad credentials")
            override suspend fun signUpEmail(email: String, password: String) = error("unused")
            override suspend fun beginGoogleSignIn() = Unit
            override suspend fun completeOAuth(callbackUrl: String) = error("unused")
            override suspend fun signOut() = Unit
        }

        val viewModel = AuthViewModel(failing)
        viewModel.restoreJob.join()

        var threw = false
        try {
            viewModel.signInEmail("me@example.com", "wrong")
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue("the failure must surface to the caller", threw)
        assertFalse(
            "a failed sign-in must not sign the user out",
            viewModel.uiState.value is AuthUiState.SignedOut
        )
    }
    /**
     * The ordering bug Reviewer caught: comparing against the latest *committed*
     * intent lets an older sign-in land while a newer sign-out is still
     * in flight. The sign-out here never completes, so it has committed
     * nothing — and the sign-in must STILL be discarded, because the user's
     * most recent expressed intent was to sign out.
     */
    @Test
    fun `a sign-in does not commit while a newer sign-out is still pending`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val signInStarted = CompletableDeferred<Unit>()

            val service = object : AuthServicing {
                override val isSupabaseConfigured = true
                override val isGoogleProviderConfigured = true
                override suspend fun currentSession(): AuthSession? = null
                override suspend fun signInEmail(email: String, password: String): AuthSession {
                    signInStarted.complete(Unit)
                    gate.await()
                    return session
                }
                override suspend fun signUpEmail(email: String, password: String) = error("unused")
                override suspend fun beginGoogleSignIn() = Unit
                override suspend fun completeOAuth(callbackUrl: String) = error("unused")
                override suspend fun signOut() {
                    // Never returns: the sign-out stays pending forever.
                    CompletableDeferred<Unit>().await()
                }
            }

            val viewModel = AuthViewModel(service)
            viewModel.restoreJob.join()

            val signIn = launch { viewModel.signInEmail("me@example.com", "pw") }
            signInStarted.await()

            // Starts (claiming a newer intent) but never completes.
            val signOut = launch { viewModel.signOut() }
            advanceUntilIdle()

            gate.complete(Unit)
            signIn.join()
            advanceUntilIdle()

            assertFalse(
                "an older sign-in committed while a newer sign-out was pending",
                viewModel.uiState.value is AuthUiState.Authenticated
            )
            signOut.cancel()
        }

    /**
     * Same rule, but the newer intent FAILED rather than hanging. A failed
     * sign-out still expressed the user's intent to sign out, so the older
     * sign-in must not be applied after it.
     */
    @Test
    fun `a sign-in does not commit after a newer sign-in has already failed`() =
        runTest(dispatcher) {
            val firstStarted = CompletableDeferred<Unit>()
            val firstGate = CompletableDeferred<Unit>()
            var call = 0

            val service = object : AuthServicing {
                override val isSupabaseConfigured = true
                override val isGoogleProviderConfigured = true
                override suspend fun currentSession(): AuthSession? = null
                override suspend fun signInEmail(email: String, password: String): AuthSession {
                    return when (++call) {
                        1 -> {
                            firstStarted.complete(Unit)
                            firstGate.await()
                            session
                        }
                        else -> throw IllegalStateException("bad credentials")
                    }
                }
                override suspend fun signUpEmail(email: String, password: String) = error("unused")
                override suspend fun beginGoogleSignIn() = Unit
                override suspend fun completeOAuth(callbackUrl: String) = error("unused")
                override suspend fun signOut() = Unit
            }

            val viewModel = AuthViewModel(service)
            viewModel.restoreJob.join()

            val slow = launch { viewModel.signInEmail("me@example.com", "pw") }
            firstStarted.await()

            // A newer attempt starts and fails.
            runCatching { viewModel.signInEmail("me@example.com", "wrong") }
            advanceUntilIdle()

            firstGate.complete(Unit)
            slow.join()
            advanceUntilIdle()

            assertFalse(
                "a superseded sign-in committed after a newer attempt had failed",
                viewModel.uiState.value is AuthUiState.Authenticated
            )
        }

    /**
     * Cancellation must propagate, not be folded into a state.
     *
     * Swallowing CancellationException detaches the coroutine from its scope:
     * the ViewModel can be cleared while the auth call keeps running, which
     * breaks structured concurrency rather than merely being untidy.
     */
    @Test
    fun `a cancelled restore propagates cancellation instead of resolving`() =
        runTest(dispatcher) {
            val started = CompletableDeferred<Unit>()
            val service = object : AuthServicing {
                override val isSupabaseConfigured = true
                override val isGoogleProviderConfigured = true
                override suspend fun currentSession(): AuthSession? {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    return null
                }
                override suspend fun signInEmail(email: String, password: String) = error("unused")
                override suspend fun signUpEmail(email: String, password: String) = error("unused")
                override suspend fun beginGoogleSignIn() = Unit
                override suspend fun completeOAuth(callbackUrl: String) = error("unused")
                override suspend fun signOut() = Unit
            }

            val viewModel = AuthViewModel(service)
            started.await()
            viewModel.restoreJob.cancel()
            viewModel.restoreJob.join()

            assertTrue("a cancelled restore must not be converted to a state", viewModel.restoreJob.isCancelled)
            assertTrue(viewModel.uiState.value is AuthUiState.Restoring)
        }

    /**
     * Launching the Google consent page is NOT authentication: the library call
     * returns as soon as the browser opens. Claiming a session there reported
     * "no session" on a first-ever login, so the router must stay put until the
     * callback arrives.
     */
    @Test
    fun `beginning Google sign-in does not change the router state`() = runTest(dispatcher) {
        var launched = false
        val service = object : AuthServicing {
            override val isSupabaseConfigured = true
            override val isGoogleProviderConfigured = true
            override suspend fun currentSession(): AuthSession? = null
            override suspend fun signInEmail(email: String, password: String) = error("unused")
            override suspend fun signUpEmail(email: String, password: String) = error("unused")
            override suspend fun beginGoogleSignIn() { launched = true }
            override suspend fun completeOAuth(callbackUrl: String) = session
            override suspend fun signOut() = Unit
        }

        val viewModel = AuthViewModel(service)
        viewModel.restoreJob.join()

        viewModel.beginGoogleSignIn()
        assertTrue("the consent page must actually be opened", launched)
        assertTrue(
            "launching OAuth must not claim authentication",
            viewModel.uiState.value is AuthUiState.SignedOut
        )

        // The callback is what authenticates.
        viewModel.handleOAuthCallback("com.maghizhan.tabby://auth-callback?code=abc")
        assertTrue(viewModel.uiState.value is AuthUiState.Authenticated)
    }

    /**
     * A FAILED callback exchange must resolve the router, not leave it in
     * Restoring.
     *
     * This is the cold-start case: the app is launched BY the callback, so the
     * restore and the callback race. The callback claims the newer intent, which
     * means the restore's result is correctly rejected when it lands — so if the
     * callback itself commits nothing on failure, nothing ever resolves and the
     * user is stuck on the splash screen with no way forward.
     */
    @Test
    fun `a failed cold-start callback resolves to SignedOut with a reason`() =
        runTest(dispatcher) {
            val restoreGate = CompletableDeferred<Unit>()
            val service = object : AuthServicing {
                override val isSupabaseConfigured = true
                override val isGoogleProviderConfigured = true
                override suspend fun currentSession(): AuthSession? {
                    // Still restoring when the callback arrives, as on a cold start.
                    restoreGate.await()
                    return null
                }
                override suspend fun signInEmail(email: String, password: String) = error("unused")
                override suspend fun signUpEmail(email: String, password: String) = error("unused")
                override suspend fun beginGoogleSignIn() = Unit
                override suspend fun completeOAuth(callbackUrl: String): AuthSession =
                    throw IllegalStateException("PKCE exchange rejected")
                override suspend fun signOut() = Unit
            }

            val viewModel = AuthViewModel(service)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is AuthUiState.Restoring)

            viewModel.handleOAuthCallback("com.maghizhan.tabby://auth-callback?code=bad")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("a failed callback must resolve the router", state is AuthUiState.SignedOut)
            assertNotNull(
                "the user needs to be told why sign-in failed",
                (state as AuthUiState.SignedOut).error
            )

            // The superseded restore must not then overwrite the failure.
            restoreGate.complete(Unit)
            viewModel.restoreJob.join()
            advanceUntilIdle()
            assertNotNull((viewModel.uiState.value as AuthUiState.SignedOut).error)
        }

    @Test
    fun `a newer sign-out is not overwritten by a failing callback`() = runTest(dispatcher) {
        val service = object : AuthServicing {
            override val isSupabaseConfigured = true
            override val isGoogleProviderConfigured = true
            override suspend fun currentSession(): AuthSession? = null
            override suspend fun signInEmail(email: String, password: String) = error("unused")
            override suspend fun signUpEmail(email: String, password: String) = error("unused")
            override suspend fun beginGoogleSignIn() = Unit
            override suspend fun completeOAuth(callbackUrl: String): AuthSession =
                throw IllegalStateException("denied")
            override suspend fun signOut() = Unit
        }

        val viewModel = AuthViewModel(service)
        viewModel.restoreJob.join()

        val callback = launch {
            viewModel.handleOAuthCallback("com.maghizhan.tabby://auth-callback?code=bad")
        }
        viewModel.signOut()
        callback.join()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is AuthUiState.SignedOut)
    }
}
