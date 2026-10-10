package com.maghizhan.tabby.account

import com.maghizhan.tabby.analytics.AnalyticsEvent

interface AccountDeletionAnalytics {
    suspend fun track(event: AnalyticsEvent)
    suspend fun flush()
    suspend fun clear()
}

interface LocalAccountData {
    suspend fun clearRoom()
    suspend fun clearPreferences()
    suspend fun clearWidgets()
}

/**
 * The destructive boundary for immediate account deletion.
 *
 * Local state is intentionally preserved when the server rejects deletion: the
 * account still exists, so erasing the device first would turn a recoverable
 * network/auth error into silent data loss. After the server succeeds, cleanup
 * is local-only and must complete before routing to Login.
 */
class AccountDeletionCoordinator(
    private val analytics: AccountDeletionAnalytics,
    private val remote: suspend () -> Unit,
    private val local: LocalAccountData,
    private val signOutLocal: suspend () -> Unit
) {
    suspend fun deleteImmediately() {
        analytics.track(AnalyticsEvent.AccountDeleted)
        analytics.flush()
        remote()
        local.clearRoom()
        local.clearPreferences()
        local.clearWidgets()
        analytics.clear()
        signOutLocal()
    }
}
