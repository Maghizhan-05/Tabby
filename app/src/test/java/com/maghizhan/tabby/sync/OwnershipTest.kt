package com.maghizhan.tabby.sync

import com.maghizhan.tabby.data.sync.Ownership
import com.maghizhan.tabby.data.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the iOS ownership rules in `ExpenseOwnershipAndSyncTests`. */
class OwnershipTest {

    @Test
    fun `normalization lowercases and treats blank as no owner`() {
        assertEquals("owner-a", Ownership.normalized("  OWNER-A  "))
        assertNull(Ownership.normalized("   "))
        assertNull(Ownership.normalized(null))
    }

    @Test
    fun `a legacy unowned record is visible to the signed-in user`() {
        assertTrue(Ownership.isAccessible(recordOwnerId = null, activeOwnerId = "owner-a"))
    }

    @Test
    fun `another accounts record is never accessible`() {
        assertFalse(Ownership.isAccessible(recordOwnerId = "owner-b", activeOwnerId = "owner-a"))
    }

    @Test
    fun `a signed-out session matches nothing`() {
        assertFalse(Ownership.isAccessible(recordOwnerId = null, activeOwnerId = null))
        assertFalse(Ownership.isAccessible(recordOwnerId = "owner-a", activeOwnerId = "  "))
    }

    @Test
    fun `a legacy record is claimed by the active owner on push`() {
        assertEquals(
            "owner-a",
            Ownership.resolvedOwnerId(recordOwnerId = null, activeOwnerId = "owner-a")
        )
    }

    @Test
    fun `a push never re-stamps another accounts record`() {
        assertNull(
            "resolving must refuse rather than steal the record",
            Ownership.resolvedOwnerId(recordOwnerId = "owner-b", activeOwnerId = "owner-a")
        )
    }

    @Test
    fun `an owned record keeps its original id`() {
        assertEquals(
            "owner-a",
            Ownership.resolvedOwnerId(recordOwnerId = "OWNER-A", activeOwnerId = "owner-a")
        )
    }

    @Test
    fun `a never-pushed record is removed outright rather than tombstoned`() {
        assertEquals(
            Ownership.DeletionPlan.REMOVE_LOCALLY,
            Ownership.deletionPlan(SyncState.LOCAL, remoteId = null)
        )
    }

    @Test
    fun `anything the backend may have seen is tombstoned`() {
        assertEquals(
            Ownership.DeletionPlan.TOMBSTONE,
            Ownership.deletionPlan(SyncState.LOCAL, remoteId = "remote-1")
        )
        assertEquals(
            Ownership.DeletionPlan.TOMBSTONE,
            Ownership.deletionPlan(SyncState.SYNCED, remoteId = null)
        )
        assertEquals(
            Ownership.DeletionPlan.TOMBSTONE,
            Ownership.deletionPlan(SyncState.DIRTY, remoteId = null)
        )
    }
}
