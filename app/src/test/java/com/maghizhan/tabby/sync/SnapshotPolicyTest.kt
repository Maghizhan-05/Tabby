package com.maghizhan.tabby.sync

import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.sync.ExpenseReconciliation
import com.maghizhan.tabby.data.sync.SyncState
import com.maghizhan.tabby.data.remote.model.LocalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The snapshot's two safety properties, at the reconciliation layer.
 *
 * Both exist because absence-based deletion is only sound against a result set
 * that is provably complete AND provably the right account's. Get either wrong
 * and reconciliation destroys live records.
 */
class SnapshotPolicyTest {

    private val ownerA = "11111111-1111-1111-1111-111111111111"
    private val ownerB = "22222222-2222-2222-2222-222222222222"
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun remote(id: UUID, owner: String) = RemoteExpenseRow(
        id = id,
        userId = owner,
        amount = BigDecimal("10.00"),
        categoryName = "Coffee",
        note = null,
        date = t0,
        createdAt = t0,
        updatedAt = t0
    )

    private fun localSynced(id: UUID, owner: String) = LocalRecord(
        id = id,
        ownerId = owner,
        updatedAt = t0,
        syncState = SyncState.SYNCED,
        hasRemoteIdentity = true
    )

    /**
     * A proven snapshot authorises deletion; this is the baseline the next test
     * is contrasted against, so a false pass is visible.
     */
    @Test
    fun `a proven snapshot authorises absence deletion`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(localSynced(id, ownerA)),
            remote = CompleteSnapshot.forTesting(emptyList<RemoteExpenseRow>(), ownerA),
            activeOwnerId = ownerA
        )
        assertEquals(listOf(id), plan.deletions)
    }

    /**
     * The shifting-offset defect: a row skipped between paged requests is
     * indistinguishable from a row deleted elsewhere, so an unproven snapshot
     * must never delete. Degrading to "merge now, delete next cycle"
     * self-corrects; deleting destroys data on a transient race.
     */
    @Test
    fun `an unproven snapshot authorises no deletion`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(localSynced(id, ownerA)),
            remote = CompleteSnapshot.forTesting(
                emptyList<RemoteExpenseRow>(),
                ownerA,
                authorizesAbsenceDeletion = false
            ),
            activeOwnerId = ownerA
        )
        assertTrue("an unproven snapshot deleted a live row", plan.deletions.isEmpty())
    }

    /** An unproven snapshot is still useful: merges proceed. */
    @Test
    fun `an unproven snapshot still inserts new remote rows`() {
        val incoming = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = emptyList(),
            remote = CompleteSnapshot.forTesting(
                listOf(remote(incoming, ownerA)),
                ownerA,
                authorizesAbsenceDeletion = false
            ),
            activeOwnerId = ownerA
        )
        assertEquals(listOf(incoming), plan.inserts.map { it.id })
    }

    @Test
    fun `a snapshot refuses to be read by the wrong owner`() {
        val snapshot = CompleteSnapshot.forTesting(emptyList<RemoteExpenseRow>(), ownerA)
        assertThrows(CompleteSnapshot.OwnerMismatchException::class.java) {
            snapshot.requireOwner(ownerB)
        }
    }

    @Test
    fun `a snapshot is readable by its own owner regardless of case or padding`() {
        val snapshot = CompleteSnapshot.forTesting(emptyList<RemoteExpenseRow>(), ownerA.uppercase())
        // Supabase ids are case-insensitive UUID strings; padding comes from
        // sloppy callers. Neither should look like a different account.
        assertTrue(snapshot.requireOwner("  $ownerA  ").isEmpty())
    }

    @Test
    fun `a proved snapshot reports that it authorises deletion`() {
        val snapshot = CompleteSnapshot.proved(
            rows = listOf(remote(UUID.randomUUID(), ownerA)),
            ownerId = ownerA,
            pagesFetched = 1
        )
        assertTrue(snapshot.authorizesAbsenceDeletion)
    }

    @Test
    fun `a partial snapshot reports that it does not`() {
        val snapshot = CompleteSnapshot.partial(
            rows = listOf(remote(UUID.randomUUID(), ownerA)),
            ownerId = ownerA,
            pagesFetched = 3
        )
        assertFalse(snapshot.authorizesAbsenceDeletion)
        assertEquals(3, snapshot.pagesFetched)
    }
}
