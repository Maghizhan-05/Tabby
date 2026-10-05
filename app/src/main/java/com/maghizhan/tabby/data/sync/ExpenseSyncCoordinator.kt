package com.maghizhan.tabby.data.sync

import com.maghizhan.tabby.data.local.ExpenseDao
import com.maghizhan.tabby.data.local.TransactionRunner
import com.maghizhan.tabby.data.local.WriteGuard
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.ExpenseRepositoring
import com.maghizhan.tabby.data.remote.NotAuthenticatedException
import com.maghizhan.tabby.data.remote.model.LocalRecord
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import kotlinx.coroutines.CancellationException

/**
 * The production expense sync cycle: the thing that actually wires the DAO, the
 * repository and the pure reconciliation rules together.
 *
 * Order is fixed and enforced here rather than left to call sites:
 *
 *   1. read the active owner from the live session
 *   2. pull a provably complete, owner-bound snapshot
 *   3. re-assert the owner, then apply the merge plan in ONE transaction
 *   4. only then push local work
 *
 * Why each step is non-negotiable:
 *
 * - **Pull before push.** Absence from a complete snapshot is read as "deleted
 *   on another device". Pushing first re-uploads a row another device deleted,
 *   and the next snapshot then confirms it forever — the iOS resurrection bug.
 * - **One transaction.** The plan deletes and inserts together. Applied
 *   piecemeal, a crash or a cancellation between the two leaves the store with
 *   deletions applied and inserts missing, which looks exactly like data loss
 *   to the user and like "deleted elsewhere" to the next cycle.
 * - **A failed pull aborts the whole cycle.** No local mutation, no push. An
 *   error is not an empty account, and acting on a partial view is how a
 *   transient network failure becomes permanent deletion.
 */
class ExpenseSyncCoordinator(
    private val dao: ExpenseDao,
    private val repository: ExpenseRepositoring,
    private val transactions: TransactionRunner,
    /** Reads the authenticated user id; null when signed out. */
    private val activeOwnerId: suspend () -> String?
) {

    /** What a cycle actually did, so callers and tests can assert on it. */
    data class Outcome(
        val pulled: Int,
        val inserted: Int,
        val updated: Int,
        val deletedLocally: Int,
        val pushed: Int,
        val deletedRemotely: Int
    )

    /**
     * Runs one full cycle. Throws on failure, having mutated nothing if the
     * failure happened before the transaction committed.
     */
    suspend fun synchronize(): Outcome {
        val owner = activeOwnerId() ?: throw NotAuthenticatedException()

        // 1. PULL - before any local mutation or upload.
        val snapshot: CompleteSnapshot<RemoteExpenseRow> = repository.fetchAll()

        // 2. RECONCILE + APPLY, atomically. requireOwner is re-checked against
        // the owner read fresh here: if the account switched while the fetch was
        // in flight, the snapshot belongs to the previous account and applying
        // it would absence-delete the new account's synced rows.
        val ownerNow = activeOwnerId() ?: throw NotAuthenticatedException()
        val rows = snapshot.requireOwner(ownerNow)

        var inserted = 0
        var updated = 0
        var deletedLocally = 0

        transactions.inTransaction {
            val localRecords = dao.allForOwner(ownerNow).map { it.toLocalRecord() }
            val plan = ExpenseReconciliation.plan(
                local = localRecords,
                remote = snapshot,
                activeOwnerId = ownerNow
            )

            if (plan.inserts.isNotEmpty()) {
                dao.upsert(plan.inserts.map { it.toEntity(ownerNow) })
                inserted = plan.inserts.size
            }
            if (plan.updates.isNotEmpty()) {
                val existing = dao.allForOwner(ownerNow).associateBy { it.id }
                dao.upsert(
                    plan.updates.mapNotNull { update ->
                        existing[update.id]?.let { update.row.toEntity(ownerNow, it.revision) }
                    }
                )
                updated = plan.updates.size
            }
            if (plan.deletions.isNotEmpty()) {
                dao.deleteByIds(plan.deletions, ownerNow)
                deletedLocally = plan.deletions.size
            }
        }

        // 3. PUSH - only now that the local store reflects the backend.
        val pending = dao.pendingPush(ownerNow)
        val tombstones = pending.filter { it.syncState == SyncState.DELETED }
        val upserts = pending.filter { it.syncState != SyncState.DELETED }

        if (upserts.isNotEmpty()) {
            // Validated again at the upload boundary: a row could have been
            // written before a validation rule existed, and the backend column
            // is the constraint that actually matters.
            repository.upsert(upserts.map { it.toRemoteRow() })
            transactions.inTransaction {
                dao.markSynced(upserts.map { it.id }, ownerNow)
            }
        }

        if (tombstones.isNotEmpty()) {
            repository.delete(tombstones.map { it.id })
            transactions.inTransaction {
                // Tombstones are removed only after the remote delete succeeds,
                // so a failed delete is retried next cycle instead of silently
                // leaving a row alive remotely.
                dao.deleteByIds(tombstones.map { it.id }, ownerNow)
            }
        }

        return Outcome(
            pulled = rows.size,
            inserted = inserted,
            updated = updated,
            deletedLocally = deletedLocally,
            pushed = upserts.size,
            deletedRemotely = tombstones.size
        )
    }

    /**
     * Runs a cycle, converting any failure into `null` rather than throwing, for
     * callers (pull-to-refresh, app start) where a failed sync is not an error
     * the user must act on. Cancellation still propagates.
     */
    suspend fun synchronizeQuietly(): Outcome? = try {
        synchronize()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }
}

private fun ExpenseEntity.toLocalRecord(): LocalRecord = LocalRecord(
    id = id,
    ownerId = ownerId,
    updatedAt = updatedAt,
    syncState = syncState,
    hasRemoteIdentity = remoteId != null
)

private fun ExpenseEntity.toRemoteRow(): RemoteExpenseRow = RemoteExpenseRow(
    id = id,
    // Overwritten with the session's id by the repository; never trusted from here.
    userId = ownerId.orEmpty(),
    amount = MoneyValidation.normalizedAmount(amount, "amount"),
    categoryName = categoryName,
    note = note,
    date = date,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun RemoteExpenseRow.toEntity(ownerId: String, revision: Int = 0): ExpenseEntity =
    ExpenseEntity(
        id = id,
        amount = MoneyValidation.normalizedAmount(amount, "amount"),
        categoryName = categoryName,
        note = ExpenseEntity.normalizedNote(note),
        date = date,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStateRaw = SyncState.SYNCED.raw,
        remoteId = id.toString(),
        ownerId = ownerId,
        revision = revision
    )
