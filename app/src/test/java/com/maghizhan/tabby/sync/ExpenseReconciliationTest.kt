package com.maghizhan.tabby.sync

import com.maghizhan.tabby.data.remote.model.LocalRecord
import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.sync.ExpenseReconciliation
import com.maghizhan.tabby.data.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Pure merge-rule tests for bidirectional expense sync, ported scenario-for-
 * scenario from the iOS `ExpenseReconciliationTests`. No Room or network
 * involved — only `ExpenseReconciliation.plan`.
 */
class ExpenseReconciliationTest {

    private val ownerA = "owner-a"
    private val ownerB = "owner-b"

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private val t1: Instant = Instant.ofEpochSecond(1_700_000_500)

    private fun row(
        id: UUID,
        owner: String,
        amount: BigDecimal = BigDecimal("10"),
        category: String = "Food",
        note: String? = null,
        updatedAt: Instant
    ) = RemoteExpenseRow(
        id = id,
        userId = owner,
        amount = amount,
        categoryName = category,
        note = note,
        date = updatedAt,
        createdAt = updatedAt,
        updatedAt = updatedAt
    )

    private fun local(id: UUID, owner: String?, updatedAt: Instant, state: SyncState) =
        LocalRecord(id = id, ownerId = owner, updatedAt = updatedAt, syncState = state)

    // region Cross-device create

    @Test
    fun `remote row with no local counterpart is inserted`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = emptyList(),
            remote = snapshot(row(id = id, owner = ownerA, amount = BigDecimal("42"), updatedAt = t0)),
            activeOwnerId = ownerA
        )

        assertEquals(listOf(id), plan.inserts.map { it.id })
        assertEquals(BigDecimal("42"), plan.inserts.first().amount)
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.deletions.isEmpty())
    }

    @Test
    fun `newer remote version of a synced row is applied`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(local(id, ownerA, t0, SyncState.SYNCED)),
            remote = snapshot(row(id = id, owner = ownerA, amount = BigDecimal("99"), updatedAt = t1)),
            activeOwnerId = ownerA
        )

        assertEquals(listOf(id), plan.updates.map { it.id })
        assertEquals(BigDecimal("99"), plan.updates.first().row.amount)
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.deletions.isEmpty())
    }

    @Test
    fun `older or equal remote version is ignored`() {
        val olderId = UUID.randomUUID()
        val sameId = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(
                local(olderId, ownerA, t1, SyncState.SYNCED),
                local(sameId, ownerA, t0, SyncState.SYNCED)
            ),
            remote = snapshot(
                row(id = olderId, owner = ownerA, updatedAt = t0),
                row(id = sameId, owner = ownerA, updatedAt = t0)
            ),
            activeOwnerId = ownerA
        )

        assertTrue(plan.isEmpty)
    }

    // endregion

    // region Repeated pulls are no-ops

    @Test
    fun `repeated pull with no changes produces an empty plan`() {
        val id = UUID.randomUUID()
        val remote = snapshot(row(id = id, owner = ownerA, updatedAt = t0))
        val localRows = listOf(local(id, ownerA, t0, SyncState.SYNCED))

        repeat(3) {
            val plan = ExpenseReconciliation.plan(localRows, remote, ownerA)
            assertTrue(plan.isEmpty)
        }
    }

    @Test
    fun `duplicated remote ids across pages do not produce duplicate inserts`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = emptyList(),
            remote = snapshot(
                row(id = id, owner = ownerA, amount = BigDecimal("10"), updatedAt = t0),
                row(id = id, owner = ownerA, amount = BigDecimal("20"), updatedAt = t1)
            ),
            activeOwnerId = ownerA
        )

        assertEquals(1, plan.inserts.size)
        assertEquals("the last page's row wins", BigDecimal("20"), plan.inserts.first().amount)
    }

    // endregion

    // region Cross-device delete (absence)

    @Test
    fun `synced row absent from the snapshot is deleted locally`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(local(id, ownerA, t0, SyncState.SYNCED)),
            remote = snapshot(),
            activeOwnerId = ownerA
        )

        assertEquals(listOf(id), plan.deletions)
    }

    @Test
    fun `unpushed rows are never deleted by absence`() {
        val localOnly = UUID.randomUUID()
        val dirty = UUID.randomUUID()
        val tombstone = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(
                local(localOnly, ownerA, t0, SyncState.LOCAL),
                local(dirty, ownerA, t0, SyncState.DIRTY),
                local(tombstone, ownerA, t0, SyncState.DELETED)
            ),
            remote = snapshot(),
            activeOwnerId = ownerA
        )

        assertTrue(
            "rows that were never pushed (or whose delete is in flight) must survive a pull",
            plan.deletions.isEmpty()
        )
    }

    @Test
    fun `a row carrying a remote identity is deleted by absence even when dirty`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(
                LocalRecord(id, ownerA, t0, SyncState.DIRTY, hasRemoteIdentity = true)
            ),
            remote = snapshot(),
            activeOwnerId = ownerA
        )

        assertEquals(
            "the backend has seen this row, so absence means deleted elsewhere",
            listOf(id),
            plan.deletions
        )
    }

    // endregion

    // region Offline edit vs remote delete

    @Test
    fun `local dirty edit wins over a remote delete this cycle`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(local(id, ownerA, t1, SyncState.DIRTY)),
            remote = snapshot(),
            activeOwnerId = ownerA
        )

        // Not deleted, not overwritten - the next push re-creates it remotely.
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `local dirty edit is not overwritten by a newer remote row`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(local(id, ownerA, t0, SyncState.DIRTY)),
            remote = snapshot(row(id = id, owner = ownerA, amount = BigDecimal("99"), updatedAt = t1)),
            activeOwnerId = ownerA
        )

        assertTrue(
            "a pending local edit must not be clobbered mid-cycle; last-writer-wins on push",
            plan.updates.isEmpty()
        )
    }

    @Test
    fun `local tombstone is not resurrected by the remote row`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(local(id, ownerA, t0, SyncState.DELETED)),
            remote = snapshot(row(id = id, owner = ownerA, updatedAt = t1)),
            activeOwnerId = ownerA
        )

        assertTrue("a pending delete must not be undone by the pull", plan.isEmpty)
    }

    // endregion

    // region Owner isolation

    @Test
    fun `rows from another account are ignored in both directions`() {
        val theirRemote = UUID.randomUUID()
        val theirLocal = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(local(theirLocal, ownerB, t0, SyncState.SYNCED)),
            remote = snapshot(row(id = theirRemote, owner = ownerB, updatedAt = t0)),
            activeOwnerId = ownerA
        )

        assertTrue("another account's row must never be inserted", plan.inserts.isEmpty())
        assertTrue(
            "another account's local row must never be deleted by our snapshot",
            plan.deletions.isEmpty()
        )
    }

    @Test
    fun `an empty active owner produces no plan`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(local(id, ownerA, t0, SyncState.SYNCED)),
            remote = snapshot(),
            activeOwnerId = "   "
        )

        assertTrue("a signed-out pull must never delete anything", plan.isEmpty)
    }

    @Test
    fun `legacy unowned local row is reconciled for the signed-in user`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = listOf(local(id, null, t0, SyncState.SYNCED)),
            remote = snapshot(row(id = id, owner = ownerA, amount = BigDecimal("77"), updatedAt = t1)),
            activeOwnerId = ownerA
        )

        assertEquals(listOf(id), plan.updates.map { it.id })
        assertEquals(BigDecimal("77"), plan.updates.first().row.amount)
    }

    @Test
    fun `owner comparison ignores case`() {
        val id = UUID.randomUUID()
        val plan = ExpenseReconciliation.plan(
            local = emptyList(),
            remote = snapshot(row(id = id, owner = "OWNER-A", updatedAt = t0)),
            activeOwnerId = "owner-a"
        )

        assertEquals(listOf(id), plan.inserts.map { it.id })
    }

    // endregion
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
private fun snapshot(vararg rows: RemoteExpenseRow): CompleteSnapshot<RemoteExpenseRow> =
    CompleteSnapshot.forTesting(rows.toList(), ownerId = "snapshot-owner")

/**
 * A snapshot that could NOT be proven complete. Merges are still allowed from
 * one of these, but no deletion may be derived from absence.
 */
private fun unprovenSnapshot(vararg rows: RemoteExpenseRow): CompleteSnapshot<RemoteExpenseRow> =
    CompleteSnapshot.forTesting(
        rows.toList(),
        ownerId = "snapshot-owner",
        authorizesAbsenceDeletion = false
    )
