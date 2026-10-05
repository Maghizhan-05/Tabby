package com.maghizhan.tabby.data.local

import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.local.entity.FriendEntity
import com.maghizhan.tabby.data.sync.MoneyValidation
import com.maghizhan.tabby.data.sync.SyncState

/** Raised when a write targets a record another account already owns. */
class ForeignOwnerException(existingOwner: String?, activeOwner: String) : IllegalStateException(
    "Record owned by ${existingOwner ?: "no one"} cannot be written by $activeOwner."
)

/**
 * The only way application code writes local records.
 *
 * Why a store rather than a validator helper: the previous `WriteGuard` checked
 * the *incoming* row and nothing else, so an upsert carrying a UUID that already
 * belonged to another account passed the check and then overwrote that account's
 * row — a primary-key replacement has no WHERE clause to stop it. Catching that
 * requires reading the existing row and writing in the SAME transaction;
 * otherwise the row can change between the check and the write.
 *
 * Each store therefore: reads the existing row unscoped, refuses if it belongs
 * to someone else, normalizes money, stamps the active owner, bumps the
 * revision, and persists — all inside one transaction.
 *
 * The DAOs' raw `@Upsert`s stay internal to the data layer; the sync coordinator
 * uses them for backend-sourced rows, which are authoritative by definition and
 * already owner-filtered by reconciliation.
 */
class ExpenseStore(
    private val dao: ExpenseDao,
    private val transactions: TransactionRunner
) {
    /**
     * Persists a user edit. Returns the stored row.
     *
     * The revision is bumped here, which is what makes compare-and-set
     * acknowledgement work: an edit landing during an upload changes the
     * revision, so the in-flight acknowledgement no longer matches and the edit
     * survives to be pushed next cycle.
     */
    suspend fun save(entity: ExpenseEntity, activeOwnerId: String): ExpenseEntity =
        transactions.inTransaction {
            val existing = dao.byIdUnscoped(entity.id)
            if (existing != null && !existing.ownerId.equals(activeOwnerId, ignoreCase = true)) {
                throw ForeignOwnerException(existing.ownerId, activeOwnerId)
            }
            val authorized = entity.copy(
                ownerId = activeOwnerId,
                amount = MoneyValidation.normalizedAmount(entity.amount, "amount"),
                note = ExpenseEntity.normalizedNote(entity.note),
                revision = (existing?.revision ?: entity.revision) + 1
            )
            dao.upsert(listOf(authorized))
            authorized
        }

    /**
     * Marks a record for deletion rather than removing it.
     *
     * A tombstone, not a hard delete: the backend must be told, and a row erased
     * locally before that would simply reappear on the next pull.
     */
    suspend fun markDeleted(entity: ExpenseEntity, activeOwnerId: String): ExpenseEntity =
        save(
            entity.copy(syncStateRaw = SyncState.DELETED.raw),
            activeOwnerId
        )
}

class CategoryStore(
    private val dao: CategoryDao,
    private val transactions: TransactionRunner
) {
    suspend fun save(entity: CategoryEntity, activeOwnerId: String): CategoryEntity =
        transactions.inTransaction {
            val existing = dao.byIdUnscoped(entity.id)

            // A seeded default is shared across accounts and must never be
            // claimed by whoever happens to be signed in, or it would start
            // syncing and could be deleted for everyone.
            if (existing?.isDefault == true || entity.isDefault) {
                dao.upsert(listOf(entity))
                return@inTransaction entity
            }

            if (existing != null && !existing.ownerId.equals(activeOwnerId, ignoreCase = true)) {
                throw ForeignOwnerException(existing.ownerId, activeOwnerId)
            }
            val authorized = entity.copy(
                ownerId = activeOwnerId,
                revision = (existing?.revision ?: entity.revision) + 1
            )
            dao.upsert(listOf(authorized))
            authorized
        }
}

class FriendStore(
    private val dao: FriendDao,
    private val transactions: TransactionRunner
) {
    suspend fun save(entity: FriendEntity, activeOwnerId: String): FriendEntity =
        transactions.inTransaction {
            val existing = dao.byIdUnscoped(entity.id)
            if (existing != null && !existing.ownerId.equals(activeOwnerId, ignoreCase = true)) {
                throw ForeignOwnerException(existing.ownerId, activeOwnerId)
            }
            val authorized = entity.copy(
                ownerId = activeOwnerId,
                theyOweUs = MoneyValidation.normalizedBalance(entity.theyOweUs, "they_owe_us"),
                weOweThem = MoneyValidation.normalizedBalance(entity.weOweThem, "we_owe_them"),
                revision = (existing?.revision ?: entity.revision) + 1
            )
            dao.upsert(listOf(authorized))
            authorized
        }

    suspend fun markDeleted(entity: FriendEntity, activeOwnerId: String): FriendEntity =
        save(entity.copy(syncStateRaw = SyncState.DELETED.raw), activeOwnerId)
}
