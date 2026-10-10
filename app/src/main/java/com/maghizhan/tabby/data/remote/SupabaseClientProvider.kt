package com.maghizhan.tabby.data.remote

import com.maghizhan.tabby.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

/**
 * Builds the Supabase client from build-config values, mirroring how the iOS app
 * reads its configuration rather than hard-coding credentials.
 *
 * Values come from `local.properties` (gitignored) via `app/build.gradle.kts`,
 * so no key is committed. When they are absent the app still builds and runs:
 * [client] is null, [AuthServicing.isSupabaseConfigured] is false, and the UI
 * shows the not-configured message instead of crashing on launch.
 */
object SupabaseClientProvider {

    val url: String = BuildConfig.SUPABASE_URL
    val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    val isConfigured: Boolean = url.isNotBlank() && publishableKey.isNotBlank()

    val client: SupabaseClient? by lazy {
        if (!isConfigured) return@lazy null
        createSupabaseClient(supabaseUrl = url, supabaseKey = publishableKey) {
            install(Auth) {
                // Supabase-kt persists and refreshes the session itself; this is
                // what the three-state router waits for on launch.
                alwaysAutoRefresh = true
                autoLoadFromStorage = true

                // PKCE rather than the implicit flow: the code arrives at our
                // custom-scheme callback and is exchanged for a session with a
                // verifier the SDK holds, so an intercepted redirect is not
                // enough to obtain tokens. The scheme/host must match the
                // manifest's callback intent filter exactly.
                flowType = FlowType.PKCE
                scheme = OAuthCallback.SCHEME
                host = OAuthCallback.HOST
            }
            install(Postgrest)
        }
    }

    /** The authenticated user's id, or null when there is no live session. */
    fun currentUserId(): String? = client?.auth?.currentUserOrNull()?.id
}
