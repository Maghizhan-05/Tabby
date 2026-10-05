package com.maghizhan.tabby.data.remote

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Supabase-backed [AuthServicing].
 *
 * The important part for the login flash: [currentSession] does not ask "is
 * there a session right now?" — on a cold launch the SDK is still loading the
 * stored session, and that question answers "no" before answering "yes". It
 * instead waits for the session status to leave its initializing state, which is
 * precisely what the router's RESTORING state exists to cover.
 *
 * OAuth is two-phase on purpose. [beginGoogleSignIn] only opens the consent
 * page; the session appears when the custom-scheme callback reaches
 * [completeOAuth], which validates the URL before exchanging its PKCE code.
 */
class SupabaseAuthService : AuthServicing {

    private val client get() = SupabaseClientProvider.client

    override val isSupabaseConfigured: Boolean = SupabaseClientProvider.isConfigured

    /**
     * Google availability cannot be probed from the client SDK; it is a project
     * setting. Treated as available whenever Supabase itself is configured, and
     * a provider that is actually disabled surfaces as an error at sign-in.
     */
    override val isGoogleProviderConfigured: Boolean = SupabaseClientProvider.isConfigured

    override suspend fun currentSession(): AuthSession? {
        val auth = client?.auth ?: throw AuthError.NotConfigured
        // Waits out Initializing (loading from storage / refreshing) so a
        // restored session is never misread as "signed out".
        val status = auth.sessionStatus.first { it !is SessionStatus.Initializing }
        return when (status) {
            is SessionStatus.Authenticated -> status.session.user?.let {
                AuthSession(userId = it.id, email = it.email)
            }
            else -> null
        }
    }

    override suspend fun signInEmail(email: String, password: String): AuthSession {
        val auth = client?.auth ?: throw AuthError.NotConfigured
        try {
            auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            throw AuthError.Underlying(e.message ?: "Sign-in failed.")
        }
        return requireSession()
    }

    override suspend fun signUpEmail(email: String, password: String): AuthSession {
        val auth = client?.auth ?: throw AuthError.NotConfigured
        try {
            auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            throw AuthError.Underlying(e.message ?: "Sign-up failed.")
        }
        // With email confirmation enabled, sign-up produces no session; the
        // caller must not be told it is authenticated.
        return currentSession() ?: throw AuthError.Underlying(
            "Check your email to confirm your account, then sign in."
        )
    }

    /**
     * Opens the Google consent page and returns immediately.
     *
     * No session is claimed here: `signInWith(Google)` returns once the browser
     * has been launched, not once the user has consented. The redirect is pinned
     * to [OAuthCallback.REDIRECT_URL] so the result comes back to our own
     * validated intent filter.
     */
    override suspend fun beginGoogleSignIn() {
        val auth = client?.auth ?: throw AuthError.NotConfigured
        try {
            auth.signInWith(Google, redirectUrl = OAuthCallback.REDIRECT_URL)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw AuthError.ProviderUnavailable("Google")
        }
    }

    /**
     * Validates the callback and exchanges its PKCE code for a session.
     *
     * The URL is classified by [OAuthCallback] first: the intent filter is
     * exported, so any app on the device can deliver one of these, and an
     * unvalidated handler would feed attacker-chosen codes to the token
     * endpoint. A provider-reported error (such as a denied consent) is surfaced
     * as a message rather than left to time out.
     */
    override suspend fun completeOAuth(callbackUrl: String): AuthSession {
        val auth = client?.auth ?: throw AuthError.NotConfigured

        val code = when (val parsed = OAuthCallback.parse(callbackUrl)) {
            is OAuthCallback.Result.Code -> parsed.value
            is OAuthCallback.Result.ProviderError -> throw AuthError.InvalidCallback(
                parsed.description ?: "Sign-in was not completed (${parsed.code})."
            )
            is OAuthCallback.Result.Invalid -> throw AuthError.InvalidCallback(parsed.reason)
            OAuthCallback.Result.NotACallback -> throw AuthError.InvalidCallback(
                "That link is not a Tabby sign-in callback."
            )
        }

        try {
            auth.exchangeCodeForSession(code)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            throw AuthError.Underlying(e.message ?: "Could not complete sign-in.")
        }
        return requireSession()
    }

    override suspend fun signOut() {
        val auth = client?.auth ?: throw AuthError.NotConfigured
        auth.signOut()
    }

    private suspend fun requireSession(): AuthSession =
        currentSession() ?: throw AuthError.Underlying("Signed in but no session was returned.")
}
