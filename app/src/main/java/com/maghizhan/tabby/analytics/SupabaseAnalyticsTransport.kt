package com.maghizhan.tabby.analytics

import com.maghizhan.tabby.data.remote.AuthError
import com.maghizhan.tabby.data.remote.NotAuthenticatedException
import com.maghizhan.tabby.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Authenticated, first-party transport. The app never reads analytics back. */
class SupabaseAnalyticsTransport {
    suspend fun upload(records: List<AnalyticsRecord>) {
        if (records.isEmpty()) return
        val client = SupabaseClientProvider.client ?: throw AuthError.NotConfigured
        val currentUser = SupabaseClientProvider.currentUserId() ?: throw NotAuthenticatedException()

        // Do not trust the cached row owner. A stale account buffer is discarded
        // rather than uploaded under another authenticated account.
        val own = records.filter { it.userId.equals(currentUser, ignoreCase = true) }
        if (own.isEmpty()) return

        client.from("analytics_events").insert(
            buildJsonArray {
                own.forEach { row ->
                    add(buildJsonObject {
                        put("id", row.id)
                        put("user_id", currentUser)
                        put("name", row.name)
                        put("occurred_at", java.time.Instant.ofEpochMilli(row.occurredAt).toString())
                        put("app_version", row.appVersion)
                        put("props", buildJsonObject { row.props.forEach { (key, value) -> put(key, value) } })
                    })
                }
            }
        )
    }
}
