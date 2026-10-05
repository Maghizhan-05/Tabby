package com.maghizhan.tabby.data.sync

/**
 * Pure ownership policy, ported from the iOS `ExpenseOwnership`. Kept free of
 * Android and Supabase imports so every rule is unit-testable in isolation.
 *
 * Ownership is *claim-once*: a legacy record created before ownership
 * partitioning (`ownerId == null`) is claimed by the first signed-in user that
 * touches it, after which the id is immutable. A record owned by another
 * account is never shown, never uploaded, and never re-stamped.
 */
object Ownership {

    /**
     * Normalizes an owner id for comparison. Supabase `auth.uid()` is canonical
     * lowercase uuid text; an empty/blank id is treated as "no owner" so a
     * signed-out state can never match a real record.
     */
    fun normalized(ownerId: String?): String? {
        val trimmed = ownerId?.trim().orEmpty()
        return if (trimmed.isEmpty()) null else trimmed.lowercase()
    }

    /**
     * True when [recordOwnerId] may be read/written by [activeOwnerId].
     * Unowned (legacy) records are claimable, so they are visible.
     * Signed out ([activeOwnerId] null/blank) matches nothing.
     */
    fun isAccessible(recordOwnerId: String?, activeOwnerId: String?): Boolean {
        val active = normalized(activeOwnerId) ?: return false
        val owner = normalized(recordOwnerId) ?: return true
        return owner == active
    }

    /**
     * The owner id to persist on a record before a push, or null when the record
     * must not be touched by this session. Already-owned records keep their
     * original id — a push NEVER re-stamps another account's record.
     */
    fun resolvedOwnerId(recordOwnerId: String?, activeOwnerId: String?): String? {
        val active = normalized(activeOwnerId) ?: return null
        val owner = normalized(recordOwnerId) ?: return active
        return if (owner == active) owner else null
    }

    /** How a local delete must be applied. */
    enum class DeletionPlan {
        /** Never synced: safe to remove from the local store immediately. */
        REMOVE_LOCALLY,

        /**
         * Exists (or may exist) remotely: keep a DELETED tombstone and remove it
         * only after the remote delete succeeds.
         */
        TOMBSTONE
    }

    fun deletionPlan(syncState: SyncState, remoteId: String?): DeletionPlan = when (syncState) {
        // Created locally and never pushed - nothing remote to clean up.
        SyncState.LOCAL -> if (remoteId == null) DeletionPlan.REMOVE_LOCALLY else DeletionPlan.TOMBSTONE
        SyncState.SYNCED, SyncState.DIRTY, SyncState.DELETED -> DeletionPlan.TOMBSTONE
    }
}
