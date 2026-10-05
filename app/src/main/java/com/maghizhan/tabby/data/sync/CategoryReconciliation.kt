package com.maghizhan.tabby.data.sync

import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.model.RemoteCategoryRow
import java.util.UUID

/**
 * Pure merge rules for bidirectional custom-category sync.
 *
 * Categories differ from expenses and friends in one important way: the seeded
 * defaults exist independently on every device with `ownerId == null`, and they
 * must never be pushed, pulled, or deleted by absence. Only custom categories —
 * the ones a user creates — participate in sync.
 */
object CategoryReconciliation {

    data class Plan(
        val inserts: List<RemoteCategoryRow> = emptyList(),
        val updates: List<RemoteCategoryRow> = emptyList(),
        val deletions: List<UUID> = emptyList()
    ) {
        val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty() && deletions.isEmpty()
    }

    data class LocalRecord(
        val id: UUID,
        val ownerId: String?,
        val name: String,
        val sortOrder: Int = 0,
        val isDefault: Boolean = false,
        val syncState: SyncState = SyncState.LOCAL,
        /** True when the backend has already seen this record. */
        val hasRemoteIdentity: Boolean = false
    )

    /**
     * Builds the merge plan.
     *
     * Rules:
     * - A seeded default (`isDefault`) is never inserted, updated or deleted.
     * - A remote row absent locally -> insert as SYNCED.
     * - A local SYNCED row whose remote name/order differs -> update.
     * - A pending local row (LOCAL / DIRTY / DELETED) keeps its local value this
     *   cycle; the following push propagates it.
     * - A local row absent from the snapshot is deleted iff the backend had seen
     *   it, matching the expense/friend rule that stops resurrection.
     * - A local row matching a remote row **by name** is adopted rather than
     *   duplicated: two devices that each created "Coffee" offline converge on
     *   the remote row's identity instead of showing it twice.
     */
    fun plan(
        local: List<LocalRecord>,
        remote: CompleteSnapshot<RemoteCategoryRow>,
        activeOwnerId: String
    ): Plan {
        val owner = Ownership.normalized(activeOwnerId) ?: return Plan()

        // Last row wins for a duplicated id across pages.
        val remoteById = LinkedHashMap<UUID, RemoteCategoryRow>()
        remote.rows.filter { Ownership.normalized(it.userId) == owner && !it.isDefault }
            .forEach { remoteById[it.id] = it }

        val localForOwner = local.filter {
            !it.isDefault && Ownership.normalized(it.ownerId) == owner
        }
        val localById = localForOwner.associateBy({ it.id }, { it })

        // Name collisions are resolved against rows the backend has NOT seen:
        // a local-only "Coffee" adopts the remote "Coffee" instead of duplicating.
        val unsyncedLocalByName = LinkedHashMap<String, LocalRecord>()
        for (record in localForOwner) {
            if (record.hasRemoteIdentity || record.syncState != SyncState.LOCAL) continue
            unsyncedLocalByName.putIfAbsent(record.name.lowercase(), record)
        }

        val inserts = mutableListOf<RemoteCategoryRow>()
        val updates = mutableListOf<RemoteCategoryRow>()
        val deletions = mutableListOf<UUID>()

        for (row in remoteById.values.sortedBy { it.id.toString() }) {
            val localRecord = localById[row.id]
            if (localRecord == null) {
                // A local-only row with the same name is about to be replaced by
                // this remote identity, so drop the duplicate and take the remote.
                unsyncedLocalByName[row.name.lowercase()]?.let { deletions += it.id }
                inserts += row
                continue
            }
            if (localRecord.syncState != SyncState.SYNCED) continue
            if (localRecord.name != row.name || localRecord.sortOrder != row.sortOrder) {
                updates += row
            }
        }

        for (record in localForOwner) {
            if (remoteById.containsKey(record.id)) continue
            if (record.syncState != SyncState.SYNCED && !record.hasRemoteIdentity) continue
            deletions += record.id
        }

        return Plan(
            inserts = inserts.sortedBy { it.id.toString() },
            updates = updates.sortedBy { it.id.toString() },
            deletions = deletions.distinct().sortedBy { it.toString() }
        )
    }
}
