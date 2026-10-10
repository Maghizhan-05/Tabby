package com.maghizhan.tabby.account

import com.maghizhan.tabby.analytics.AnalyticsEvent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionCoordinatorTest {

    @Test
    fun `account deleted event is flushed before remote deletion then local state is wiped`() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = AccountDeletionCoordinator(
            analytics = object : AccountDeletionAnalytics {
                override suspend fun track(event: AnalyticsEvent) { calls += "track" }
                override suspend fun flush() { calls += "flush" }
                override suspend fun clear() { calls += "analytics-clear" }
            },
            remote = { calls += "remote-delete" },
            local = object : LocalAccountData {
                override suspend fun clearRoom() { calls += "room-clear" }
                override suspend fun clearPreferences() { calls += "preferences-clear" }
                override suspend fun clearWidgets() { calls += "widgets-clear" }
            },
            signOutLocal = { calls += "sign-out" }
        )

        coordinator.deleteImmediately()

        assertEquals(
            listOf(
                "track", "flush", "remote-delete", "room-clear",
                "preferences-clear", "widgets-clear", "analytics-clear", "sign-out"
            ),
            calls
        )
    }

    @Test
    fun `remote failure preserves local data and session`() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = AccountDeletionCoordinator(
            analytics = object : AccountDeletionAnalytics {
                override suspend fun track(event: AnalyticsEvent) { calls += "track" }
                override suspend fun flush() { calls += "flush" }
                override suspend fun clear() { calls += "analytics-clear" }
            },
            remote = { calls += "remote-delete"; error("server unavailable") },
            local = object : LocalAccountData {
                override suspend fun clearRoom() { calls += "room-clear" }
                override suspend fun clearPreferences() { calls += "preferences-clear" }
                override suspend fun clearWidgets() { calls += "widgets-clear" }
            },
            signOutLocal = { calls += "sign-out" }
        )

        val failure = runCatching { coordinator.deleteImmediately() }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(listOf("track", "flush", "remote-delete"), calls)
    }
}
