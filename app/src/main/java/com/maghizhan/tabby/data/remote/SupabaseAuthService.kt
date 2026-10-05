package com.maghizhan.tabby.data.remote

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first

/**
 * Supabase-backed [AuthServicing].
 *
 * The important part for the login flash: [currentSession] does not ask "is
 * there a session right now?" — on a cold launch the SDK is still loading the
 * stored session, and that question answers "no" before answering "yes". It
 * instead waits for the session status to leave its loading state, which is
 * precisely what the router's RESTORING state exists to cover.
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
        // Waits out LoadingFromStorage / RefreshFailure transitions so a restored
        // session is never misread as "signed out".
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
        } catch (e: Exception) {
            throw AuthError.Underlying(e.message ?: "Sign-up failed.")
        }
        // With email confirmation enabled sign-up does not produce a session;
        // the caller must not be told it is authenticated.
        return currentSession() ?: throw AuthError.Underlying(
            "Check your email to confirm your account, then sign in."
        )
    }

    override suspend fun signInWithGoogle(): AuthSession {
        val auth = client?.auth ?: throw AuthError.NotConfigured
        try {
            // Opens the browser; the result arrives via the custom-scheme
            // callback and is completed by completeOAuth.
            auth.signInWith(Google)
        } catch (e: Exception) {
            throw AuthError.ProviderUnavailable("Google")
        }
        return requireSession()
    }

    override suspend fun completeOAuth(callbackUrl: String): AuthSession {
        val auth = client?.auth ?: throw AuthError.NotConfigured
        try {
            auth.exchangeCodeForSession(extractCode(callbackUrl))
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

    private fun extractCode(callbackUrl: String): String {
        val query = callbackUrl.substringAfter('?', "").ifEmpty {
            callbackUrl.substringAfter('#', "")
        }
        return query.split('&')
            .firstOrNull { it.startsWith("code=") }
            ?.removePrefix("code=")
            ?: throw AuthError.Underlying("Callback URL contained no authorization code.")
    }
}
