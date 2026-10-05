package com.maghizhan.tabby.data.sync

import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.model.LocalRecord
import com.maghizhan.tabby.data.remote.model.RemoteFriendRow
import java.util.UUID

/**
 * Pure merge rules for reconciling local friends against an owner-scoped,
 * *provably complete* remote snapshot. Same contract and same rules as
 * [ExpenseReconciliation] — see that type for the ordering requirement: the
 * caller MUST reconcile a complete pull before pushing, because absence from the
 * snapshot is read as "deleted on another device" and pushing first would
 * resurrect rows deleted elsewhere.
 *
 * Conflict policy is identical too: while a remote row is still PRESENT a
 * pending local edit wins, but a remote DELETION outranks a dirty local edit
 * once the row is gone remotely. See [ExpenseReconciliation.plan] for why that
 * asymmetry is deliberate.
 */
object FriendReconciliation {

    data class Update(val id: UUID, val row: RemoteFriendRow)

    data class Plan(
        val inserts: List<RemoteFriendRow> = emptyList(),
        val updates: List<Update> = emptyList(),
        val deletions: List<UUID> = emptyList()
    ) {
        val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty() && deletions.isEmpty()
    }

    /**
     * Rules (identical to expenses):
     * - Remote row absent locally -> insert, SYNCED.
     * - Local SYNCED and the remote row is strictly newer -> overwrite.
     * - Local DIRTY / LOCAL / DELETED -> remote ignored this cycle; the pending
     *   local change wins and propagates on the next push.
     * - A local row absent from the snapshot is deleted iff it was previously
     *   uploaded (SYNCED, or any state carrying a remote identity), which
     *   prevents a row deleted elsewhere from being re-uploaded forever.
     * - A row that never reached the backend is NEVER deleted by absence.
     * - Rows belonging to another account are ignored in both directions.
     */
    fun plan(
        local: List<LocalRecord>,
        remote: CompleteSnapshot<RemoteFriendRow>,
        activeOwnerId: String
    ): Plan {
        val owner = Ownership.normalized(activeOwnerId) ?: return Plan()

        val remoteById = LinkedHashMap<UUID, RemoteFriendRow>()
        remote.rows.filter { Ownership.normalized(it.userId) == owner }
            .forEach { remoteById[it.id] = it }

        val localForOwner = local.filter {
            Ownership.isAccessible(recordOwnerId = it.ownerId, activeOwnerId = owner)
        }
        val localById = localForOwner.associateBy { it.id }

        val inserts = mutableListOf<RemoteFriendRow>()
        val updates = mutableListOf<Update>()
        val deletions = mutableListOf<UUID>()

        for (id in remoteById.keys.sortedBy { it.toString() }) {
            val row = remoteById.getValue(id)
            val localRecord = localById[id]
            if (localRecord == null) {
                inserts += row
                continue
            }
            if (localRecord.syncState != SyncState.SYNCED) continue
            if (row.updatedAt > localRecord.updatedAt) {
                updates += Update(id, row)
            }
        }

        // Absence means "deleted elsewhere" only for a row the backend has seen.
        // ...and only when the snapshot PROVED itself complete. An unproven
        // snapshot (count missing, count moved mid-fetch, page ceiling) may be
        // merged from but must never authorise a deletion: a row skipped by a
        // shifting offset window is indistinguishable from a row deleted
        // elsewhere, so deleting here would destroy live data on a transient
        // race. Skipping deletions this cycle self-corrects on the next one.
        if (remote.authorizesAbsenceDeletion) {
            for (record in localForOwner) {
                if (remoteById.containsKey(record.id)) continue
                if (record.syncState != SyncState.SYNCED && !record.hasRemoteIdentity) continue
                deletions += record.id
            }
        }

        return Plan(
            inserts = inserts,
            updates = updates,
            deletions = deletions.sortedBy { it.toString() }
        )
    }
}
