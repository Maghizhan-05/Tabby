package com.maghizhan.tabby.data.remote

/** A minimal session representation independent of the backend SDK. */
enum class AuthProvider { EMAIL, GOOGLE }

data class AuthSession(
    val userId: String,
    val email: String?,
    val provider: AuthProvider = AuthProvider.EMAIL
)

/** Auth failures surfaced to the UI. */
sealed class AuthError(message: String) : Exception(message) {
    data object NotConfigured : AuthError(
        "Supabase is not configured. See the README to add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY."
    )

    data class ProviderUnavailable(val provider: String) : AuthError("$provider sign-in is not configured.")

    data class Underlying(val detail: String) : AuthError(detail)

    /** The callback URL was absent, malformed, or carried an OAuth error. */
    data class InvalidCallback(val detail: String) : AuthError(detail)
}

/**
 * Auth abstraction, mirroring the iOS `AuthServicing` protocol. The app depends
 * only on this interface so the UI and the router are testable without a network
 * or a Supabase SDK present.
 *
 * Note the split between [beginGoogleSignIn] and [completeOAuth]: an OAuth
 * sign-in is inherently two-phase on Android. The first call only opens the
 * browser, and no session exists until the custom-scheme callback is handed to
 * [completeOAuth]. Collapsing these into one call made the launch report "no
 * session" on a first-ever login, because the library returns as soon as the
 * browser opens.
 */
interface AuthServicing {
    val isSupabaseConfigured: Boolean
    val isGoogleProviderConfigured: Boolean

    suspend fun currentSession(): AuthSession?
    suspend fun signInEmail(email: String, password: String): AuthSession
    suspend fun signUpEmail(email: String, password: String): AuthSession

    /** Phase 1: opens the provider's consent page. Does NOT authenticate. */
    suspend fun beginGoogleSignIn()

    /** Phase 2: validates the callback URL and exchanges its PKCE code. */
    suspend fun completeOAuth(callbackUrl: String): AuthSession

    suspend fun reauthenticateEmail(password: String) {
        throw UnsupportedOperationException("Email re-authentication is not implemented.")
    }
    suspend fun deleteCurrentAccount() {
        throw UnsupportedOperationException("Account deletion is not implemented.")
    }

    suspend fun signOut()
}
