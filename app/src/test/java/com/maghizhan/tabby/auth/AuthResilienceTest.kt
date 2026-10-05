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
        override suspend fun signInWithGoogle() = error("unused")
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
            assertEquals("token expired", (state as AuthUiState.SignedOut).error)
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
        override suspend fun signInWithGoogle() = error("unused")
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
            override suspend fun signInWithGoogle() = error("unused")
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
            override suspend fun signInWithGoogle() = error("unused")
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
}
