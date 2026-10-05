package com.maghizhan.tabby.data.remote

/** A minimal session representation independent of the backend SDK. */
data class AuthSession(val userId: String, val email: String?)

/** Auth failures surfaced to the UI. */
sealed class AuthError(message: String) : Exception(message) {
    data object NotConfigured : AuthError(
        "Supabase is not configured. See the README to add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY."
    )

    data class ProviderUnavailable(val provider: String) : AuthError("$provider sign-in is not configured.")

    data class Underlying(val detail: String) : AuthError(detail)
}

/**
 * Auth abstraction, mirroring the iOS `AuthServicing` protocol. The app depends
 * only on this interface so the UI and the router are testable without a network
 * or a Supabase SDK present.
 */
interface AuthServicing {
    val isSupabaseConfigured: Boolean
    val isGoogleProviderConfigured: Boolean

    suspend fun currentSession(): AuthSession?
    suspend fun signInEmail(email: String, password: String): AuthSession
    suspend fun signUpEmail(email: String, password: String): AuthSession
    suspend fun signInWithGoogle(): AuthSession
    suspend fun completeOAuth(callbackUrl: String): AuthSession
    suspend fun signOut()
}
