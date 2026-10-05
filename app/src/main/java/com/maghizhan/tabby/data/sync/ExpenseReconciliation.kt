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
     * Conflict policy (matches the iOS `SyncEngine`, and deliberately explicit
     * because the two halves can look contradictory):
     *
     * - Remote row absent locally -> insert, SYNCED.
     * - Local SYNCED and the remote row is strictly newer -> overwrite from remote.
     * - Local DIRTY / LOCAL / DELETED and the remote row is still PRESENT -> the
     *   remote row is ignored this cycle; the pending local change wins and the
     *   next push propagates it.
     * - **A remote DELETION outranks a pending local edit.** A local row absent
     *   from a complete snapshot is deleted iff the backend had previously seen
     *   it — SYNCED, or any state carrying a remote identity, *including DIRTY*.
     *   This is the asymmetry worth stating plainly: while the remote row still
     *   exists a dirty local edit wins, but once the row is gone remotely the
     *   deletion wins and the local edit is dropped. Keeping the edit instead
     *   would re-upload a record another device deleted, and the next snapshot
     *   would "confirm" it forever — the resurrection bug the iOS engine was
     *   fixed for.
     * - A row that NEVER reached the backend (LOCAL with no remote identity) is
     *   never deleted by absence: absence tells us nothing about it.
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
