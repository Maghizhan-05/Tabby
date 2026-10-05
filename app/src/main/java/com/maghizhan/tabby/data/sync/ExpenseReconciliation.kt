package com.maghizhan.tabby.data.sync

import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.model.LocalRecord
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import java.util.UUID

/**
 * Pure merge rules for reconciling the local Room store against an owner-scoped,
 * *provably complete* remote snapshot. No Room or Supabase imports, so every
 * rule is unit-testable in isolation.
 *
 * Ordering contract: the caller MUST reconcile a complete pull BEFORE pushing
 * local work. Absence from the snapshot is read as "deleted on another device",
 * and pushing first would re-upload a stale local row that another device had
 * deleted — the snapshot would then "confirm" it forever. This is exactly the
 * resurrection bug the iOS `SyncEngine` was fixed for, and its ordering is
 * ported here verbatim: pull, reconcile, then push. Local tombstones and
 * genuinely new or edited rows are still pushed immediately afterwards, so
 * nothing local is lost.
 */
object ExpenseReconciliation {

    /** A single field-level update to apply to an existing local record. */
    data class Update(val id: UUID, val row: RemoteExpenseRow)

    data class Plan(
        /** Remote rows with no local counterpart: insert as SYNCED. */
        val inserts: List<RemoteExpenseRow> = emptyList(),
        /** Local SYNCED rows the remote has a newer version of. */
        val updates: List<Update> = emptyList(),
        /**
         * Ids of local SYNCED rows absent from the complete remote snapshot —
         * deleted on another device, so remove them locally.
         */
        val deletions: List<UUID> = emptyList()
    ) {
        val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty() && deletions.isEmpty()
    }

    /**
     * Builds the merge plan.
     *
     * Rules:
     * - Remote row absent locally -> insert, SYNCED.
     * - Local SYNCED and the remote row is strictly newer -> overwrite from remote.
     * - Local DIRTY / LOCAL / DELETED -> the remote row is ignored this cycle;
     *   the pending local change wins and the next push propagates it
     *   (documented last-writer-wins, never a silent drop).
     * - A local row absent from the snapshot is deleted **iff it was previously
     *   uploaded** — i.e. SYNCED, or any state carrying a remote identity. This
     *   is what stops a row deleted on another device from being re-uploaded and
     *   resurrected forever.
     * - A LOCAL row (or any row that never reached the backend) is NEVER deleted
     *   by absence: it has not been pushed yet.
     * - Rows belonging to another account (or a remote row whose `user_id` isn't
     *   the active owner) are ignored entirely in both directions.
     */
    fun plan(
        local: List<LocalRecord>,
        remote: CompleteSnapshot<RemoteExpenseRow>,
        activeOwnerId: String
    ): Plan {
        val owner = Ownership.normalized(activeOwnerId) ?: return Plan()

        // Defense in depth: never let a row from another account into the plan,
        // even if the backend or a caller handed us one.
        // Last row wins for a duplicated id so repeated/overlapping pages are
        // idempotent rather than producing duplicate inserts.
        val remoteById = LinkedHashMap<UUID, RemoteExpenseRow>()
        remote.rows.filter { Ownership.normalized(it.userId) == owner }
            .forEach { remoteById[it.id] = it }

        val localForOwner = local.filter {
            Ownership.isAccessible(recordOwnerId = it.ownerId, activeOwnerId = owner)
        }
        val localById = localForOwner.associateBy { it.id }

        val inserts = mutableListOf<RemoteExpenseRow>()
        val updates = mutableListOf<Update>()
        val deletions = mutableListOf<UUID>()

        // Deterministic ordering keeps the plan (and its tests) stable.
        for (id in remoteById.keys.sortedBy { it.toString() }) {
            val row = remoteById.getValue(id)
            val localRecord = localById[id]
            if (localRecord == null) {
                inserts += row
                continue
            }
            // A pending local change always wins this cycle.
            if (localRecord.syncState != SyncState.SYNCED) continue
            if (row.updatedAt > localRecord.updatedAt) {
                updates += Update(id, row)
            }
        }

        // Absence means "deleted elsewhere" only for a row the backend has
        // actually seen. A SYNCED row is uploaded by definition; a DIRTY or
        // DELETED row counts once it carries a remote identity.
        for (record in localForOwner) {
            if (remoteById.containsKey(record.id)) continue
            if (record.syncState != SyncState.SYNCED && !record.hasRemoteIdentity) continue
            deletions += record.id
        }

        return Plan(
            inserts = inserts,
            updates = updates,
            deletions = deletions.sortedBy { it.toString() }
        )
    }
}
