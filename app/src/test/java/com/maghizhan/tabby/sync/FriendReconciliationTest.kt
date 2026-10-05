package com.maghizhan.tabby.sync

import com.maghizhan.tabby.data.remote.model.LocalRecord
import com.maghizhan.tabby.data.remote.ActiveSession
import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.model.RemoteFriendRow
import com.maghizhan.tabby.data.sync.FriendReconciliation
import com.maghizhan.tabby.data.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Ported from the iOS friend-sync regression suite. Same rules as expenses. */
class FriendReconciliationTest {

    private val ownerA = "owner-a"
    private val ownerB = "owner-b"
    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private val t1: Instant = Instant.ofEpochSecond(1_700_000_500)

    private fun row(id: UUID, owner: String, name: String = "Sam", updatedAt: Instant) =
        RemoteFriendRow(
            id = id,
            userId = owner,
            name = name,
            theyOweUs = BigDecimal("5"),
            weOweThem = BigDecimal("2"),
            createdAt = updatedAt,
            updatedAt = updatedAt
        )

    private fun local(id: UUID, owner: String?, updatedAt: Instant, state: SyncState) =
        LocalRecord(id = id, ownerId = owner, updatedAt = updatedAt, syncState = state)

    @Test
    fun `remote friend with no local counterpart is inserted`() {
        val id = UUID.randomUUID()
        val plan = FriendReconciliation.plan(
            local = emptyList(),
            remote = snapshot(row(id, ownerA, updatedAt = t0)),
            activeOwnerId = ownerA
        )
        assertEquals(listOf(id), plan.inserts.map { it.id })
    }

    @Test
    fun `newer remote friend overwrites a synced local row`() {
        val id = UUID.randomUUID()
        val plan = FriendReconciliation.plan(
            local = listOf(local(id, ownerA, t0, SyncState.SYNCED)),
            remote = snapshot(row(id, ownerA, name = "Samantha", updatedAt = t1)),
            activeOwnerId = ownerA
        )
        assertEquals(listOf(id), plan.updates.map { it.id })
        assertEquals("Samantha", plan.updates.first().row.name)
    }

    @Test
    fun `synced friend absent from the snapshot is deleted locally`() {
        val id = UUID.randomUUID()
        val plan = FriendReconciliation.plan(
            local = listOf(local(id, ownerA, t0, SyncState.SYNCED)),
            remote = snapshot(),
            activeOwnerId = ownerA
        )
        assertEquals(listOf(id), plan.deletions)
    }

    @Test
    fun `unpushed friend is never deleted by absence`() {
        val id = UUID.randomUUID()
        val plan = FriendReconciliation.plan(
            local = listOf(local(id, ownerA, t0, SyncState.LOCAL)),
            remote = snapshot(),
            activeOwnerId = ownerA
        )
        assertTrue(plan.deletions.isEmpty())
    }

    @Test
    fun `local dirty friend edit is not overwritten by a newer remote row`() {
        val id = UUID.randomUUID()
        val plan = FriendReconciliation.plan(
            local = listOf(local(id, ownerA, t0, SyncState.DIRTY)),
            remote = snapshot(row(id, ownerA, name = "Remote", updatedAt = t1)),
            activeOwnerId = ownerA
        )
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun `friends from another account are ignored in both directions`() {
        val theirRemote = UUID.randomUUID()
        val theirLocal = UUID.randomUUID()
        val plan = FriendReconciliation.plan(
            local = listOf(local(theirLocal, ownerB, t0, SyncState.SYNCED)),
            remote = snapshot(row(theirRemote, ownerB, updatedAt = t0)),
            activeOwnerId = ownerA
        )
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.deletions.isEmpty())
    }

    @Test
    fun `duplicated friend ids across pages do not produce duplicate inserts`() {
        val id = UUID.randomUUID()
        val plan = FriendReconciliation.plan(
            local = emptyList(),
            remote = snapshot(
                row(id, ownerA, name = "First", updatedAt = t0),
                row(id, ownerA, name = "Second", updatedAt = t1)
            ),
            activeOwnerId = ownerA
        )
        assertEquals(1, plan.inserts.size)
        assertEquals("Second", plan.inserts.first().name)
    }
}


/**
 * Test-only helper: a snapshot the test author asserts is complete, so absence
 * deletion is authorised. Production code can only obtain one of these from a
 * fetch whose exact server-side count matched the rows received.
 *
 * The owner is irrelevant to [plan] itself — the snapshot's owner binding is
 * enforced by the sync coordinator via `requireOwner` — so a fixed value is
 * used here and the owner-filtering rules are driven by `activeOwnerId`.
 */
private fun snapshot(vararg rows: RemoteFriendRow): CompleteSnapshot<RemoteFriendRow> =
    CompleteSnapshot.forTesting(rows.toList(), ActiveSession("snapshot-owner", 1))

/**
 * A snapshot that could NOT be proven complete. Merges are still allowed from
 * one of these, but no deletion may be derived from absence.
 */
private fun unprovenSnapshot(vararg rows: RemoteFriendRow): CompleteSnapshot<RemoteFriendRow> =
    CompleteSnapshot.forTesting(
        rows.toList(),
        ActiveSession("snapshot-owner", 1),
        authorizesAbsenceDeletion = false
    )
