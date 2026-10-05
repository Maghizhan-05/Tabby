package com.maghizhan.tabby.sync

import com.maghizhan.tabby.data.remote.model.RemoteCategoryRow
import com.maghizhan.tabby.data.sync.CategoryReconciliation
import com.maghizhan.tabby.data.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Ported from the iOS category-sync suite. Categories carry one rule the other
 * two types do not: seeded defaults never participate in sync, and two devices
 * that each created the same-named category offline must converge rather than
 * showing it twice.
 */
class CategoryReconciliationTest {

    private val ownerA = "owner-a"
    private val ownerB = "owner-b"

    private fun row(id: UUID, owner: String, name: String, sortOrder: Int = 0, isDefault: Boolean = false) =
        RemoteCategoryRow(id = id, userId = owner, name = name, isDefault = isDefault, sortOrder = sortOrder)

    private fun local(
        id: UUID,
        owner: String?,
        name: String,
        sortOrder: Int = 0,
        isDefault: Boolean = false,
        state: SyncState = SyncState.LOCAL,
        hasRemoteIdentity: Boolean = false
    ) = CategoryReconciliation.LocalRecord(id, owner, name, sortOrder, isDefault, state, hasRemoteIdentity)

    @Test
    fun `remote custom category with no local counterpart is inserted`() {
        val id = UUID.randomUUID()
        val plan = CategoryReconciliation.plan(
            local = emptyList(),
            remote = listOf(row(id, ownerA, "Coffee")),
            activeOwnerId = ownerA
        )
        assertEquals(listOf(id), plan.inserts.map { it.id })
    }

    @Test
    fun `seeded defaults never sync in either direction`() {
        val remoteDefault = UUID.randomUUID()
        val localDefault = UUID.randomUUID()
        val plan = CategoryReconciliation.plan(
            local = listOf(local(localDefault, null, "Food", isDefault = true, state = SyncState.SYNCED)),
            remote = listOf(row(remoteDefault, ownerA, "Food", isDefault = true)),
            activeOwnerId = ownerA
        )
        assertTrue("a seeded default must never be inserted, updated or deleted", plan.isEmpty)
    }

    @Test
    fun `renamed remote category updates a synced local row`() {
        val id = UUID.randomUUID()
        val plan = CategoryReconciliation.plan(
            local = listOf(local(id, ownerA, "Coffe", state = SyncState.SYNCED)),
            remote = listOf(row(id, ownerA, "Coffee")),
            activeOwnerId = ownerA
        )
        assertEquals(listOf(id), plan.updates.map { it.id })
        assertEquals("Coffee", plan.updates.first().name)
    }

    @Test
    fun `identical remote category produces no update`() {
        val id = UUID.randomUUID()
        val plan = CategoryReconciliation.plan(
            local = listOf(local(id, ownerA, "Coffee", sortOrder = 3, state = SyncState.SYNCED)),
            remote = listOf(row(id, ownerA, "Coffee", sortOrder = 3)),
            activeOwnerId = ownerA
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a local-only category with the same name adopts the remote identity`() {
        val localId = UUID.randomUUID()
        val remoteId = UUID.randomUUID()
        val plan = CategoryReconciliation.plan(
            local = listOf(local(localId, ownerA, "Coffee", state = SyncState.LOCAL)),
            remote = listOf(row(remoteId, ownerA, "Coffee")),
            activeOwnerId = ownerA
        )

        assertEquals("the remote identity is taken", listOf(remoteId), plan.inserts.map { it.id })
        assertEquals(
            "the duplicate local-only row is dropped rather than shown twice",
            listOf(localId),
            plan.deletions
        )
    }

    @Test
    fun `name adoption ignores case`() {
        val localId = UUID.randomUUID()
        val remoteId = UUID.randomUUID()
        val plan = CategoryReconciliation.plan(
            local = listOf(local(localId, ownerA, "coffee", state = SyncState.LOCAL)),
            remote = listOf(row(remoteId, ownerA, "Coffee")),
            activeOwnerId = ownerA
        )
        assertEquals(listOf(localId), plan.deletions)
    }

    @Test
    fun `synced category absent from the snapshot is deleted locally`() {
        val id = UUID.randomUUID()
        val plan = CategoryReconciliation.plan(
            local = listOf(local(id, ownerA, "Coffee", state = SyncState.SYNCED)),
            remote = emptyList(),
            activeOwnerId = ownerA
        )
        assertEquals(listOf(id), plan.deletions)
    }

    @Test
    fun `unpushed category is never deleted by absence`() {
        val id = UUID.randomUUID()
        val plan = CategoryReconciliation.plan(
            local = listOf(local(id, ownerA, "Coffee", state = SyncState.LOCAL)),
            remote = emptyList(),
            activeOwnerId = ownerA
        )
        assertTrue(plan.deletions.isEmpty())
    }

    @Test
    fun `categories from another account are ignored`() {
        val id = UUID.randomUUID()
        val plan = CategoryReconciliation.plan(
            local = listOf(local(UUID.randomUUID(), ownerB, "Theirs", state = SyncState.SYNCED)),
            remote = listOf(row(id, ownerB, "Theirs")),
            activeOwnerId = ownerA
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a blank active owner produces no plan`() {
        val plan = CategoryReconciliation.plan(
            local = listOf(local(UUID.randomUUID(), ownerA, "Coffee", state = SyncState.SYNCED)),
            remote = emptyList(),
            activeOwnerId = "  "
        )
        assertTrue(plan.isEmpty)
    }
}
