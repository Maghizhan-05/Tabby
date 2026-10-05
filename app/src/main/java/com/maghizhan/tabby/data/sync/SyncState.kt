package com.maghizhan.tabby.data.sync

/**
 * Sync state for offline-first records. Ported 1:1 from the iOS `SyncState`,
 * including the raw integer values: the same local database semantics must hold
 * on both platforms, and the raw value is what Room persists.
 */
enum class SyncState(val raw: Int) {
    /** Created locally, never pushed. */
    LOCAL(0),

    /** Pushed and confirmed by the backend. */
    SYNCED(1),

    /** Modified locally after a prior sync. */
    DIRTY(2),

    /** Removed locally; pending remote deletion. */
    DELETED(3);

    companion object {
        /** Unknown raw values degrade to LOCAL, matching the iOS fallback. */
        fun fromRaw(raw: Int): SyncState = entries.firstOrNull { it.raw == raw } ?: LOCAL
    }
}
