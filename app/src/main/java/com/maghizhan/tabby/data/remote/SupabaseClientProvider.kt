package com.maghizhan.tabby.data.remote

import com.maghizhan.tabby.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
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
    private val key: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    val isConfigured: Boolean = url.isNotBlank() && key.isNotBlank()

    val client: SupabaseClient? by lazy {
        if (!isConfigured) return@lazy null
        createSupabaseClient(supabaseUrl = url, supabaseKey = key) {
            install(Auth) {
                // Supabase-kt persists and refreshes the session itself; this is
                // what the three-state router waits for on launch.
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
            }
            install(Postgrest)
        }
    }

    /** The authenticated user's id, or null when there is no live session. */
    fun currentUserId(): String? = client?.auth?.currentUserOrNull()?.id
}
