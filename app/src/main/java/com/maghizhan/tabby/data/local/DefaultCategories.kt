package com.maghizhan.tabby.data.local

import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.sync.SyncState
import java.util.UUID

/**
 * Seeds the default categories on first run.
 *
 * The same eight names, in the same order, as the iOS
 * `SharedModelContainer.defaultCategoryNames` — expenses reference categories by
 * NAME, so a different list (or a different spelling) would make a spend logged
 * on one platform show up uncategorised on the other.
 *
 * Seeded rows are `isDefault = true`, `ownerId = null`, `SYNCED`:
 *
 * - no owner, because they exist before any account does and are shared;
 * - SYNCED, so the coordinator never tries to push them — they are presets, not
 *   user data, and uploading them would create eight junk rows per account;
 * - `isDefault`, which is what makes the store refuse to let a signed-in user
 *   claim or delete them.
 */
object DefaultCategories {

    val NAMES = listOf(
        "Food", "Transport", "Groceries", "Bills",
        "Shopping", "Entertainment", "Health", "Other"
    )

    /**
     * Inserts the defaults only when the table is completely empty.
     *
     * Emptiness — not "are the default names present" — is the right test: a
     * user who deliberately deleted a default must not have it reappear on the
     * next launch, and a tombstoned default would otherwise be resurrected.
     */
    suspend fun seedIfNeeded(dao: CategoryDao, transactions: TransactionRunner) {
        transactions.inTransaction {
            if (dao.countAll() > 0) return@inTransaction
            dao.upsert(
                NAMES.mapIndexed { index, name ->
                    CategoryEntity(
                        id = UUID.randomUUID(),
                        name = name,
                        isDefault = true,
                        sortOrder = index,
                        syncStateRaw = SyncState.SYNCED.raw,
                        remoteId = null,
                        ownerId = null
                    )
                }
            )
        }
    }
}
