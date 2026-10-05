package com.maghizhan.tabby.data.local

import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.local.entity.FriendEntity
import com.maghizhan.tabby.data.sync.MoneyValidation
import com.maghizhan.tabby.data.sync.Ownership

/**
 * The single place local writes are authorised and normalized.
 *
 * Raw DAO upserts are primary-key replacements: handed a row belonging to
 * another account they would silently overwrite it, and handed an unvalidated
 * amount they would persist a value the backend column cannot hold (so it would
 * fail opaquely mid-sync, or be rounded). Owner checks in SELECT/DELETE
 * predicates cannot cover that, because an INSERT has no predicate.
 *
 * Every function here therefore does two things before a row reaches Room:
 * assert the active session may write it, and normalize its money to the
 * backend's `numeric(12,2)` contract.
 */
object WriteGuard {

    /** Raised when a write targets a record the active session does not own. */
    class ForeignOwnerException(recordOwner: String?, activeOwner: String) :
        IllegalStateException("Record owned by $recordOwner cannot be written by $activeOwner.")

    /**
     * Returns [entity] stamped with the owner it may be written under, with its
     * amount normalized. Throws when the record belongs to another account.
     */
    fun authorizeExpense(entity: ExpenseEntity, activeOwnerId: String): ExpenseEntity {
        val owner = Ownership.resolvedOwnerId(entity.ownerId, activeOwnerId)
            ?: throw ForeignOwnerException(entity.ownerId, activeOwnerId)
        return entity.copy(
            ownerId = owner,
            amount = MoneyValidation.normalizedAmount(entity.amount, "amount")
        )
    }

    fun authorizeFriend(entity: FriendEntity, activeOwnerId: String): FriendEntity {
        val owner = Ownership.resolvedOwnerId(entity.ownerId, activeOwnerId)
            ?: throw ForeignOwnerException(entity.ownerId, activeOwnerId)
        return entity.copy(
            ownerId = owner,
            theyOweUs = MoneyValidation.normalizedBalance(entity.theyOweUs, "they_owe_us"),
            weOweThem = MoneyValidation.normalizedBalance(entity.weOweThem, "we_owe_them")
        )
    }

    /**
     * Categories carry no money. A seeded default keeps `ownerId == null` and is
     * shared, so it is passed through unchanged rather than being claimed by
     * whoever happens to be signed in.
     */
    fun authorizeCategory(entity: CategoryEntity, activeOwnerId: String): CategoryEntity {
        if (entity.isDefault) return entity
        val owner = Ownership.resolvedOwnerId(entity.ownerId, activeOwnerId)
            ?: throw ForeignOwnerException(entity.ownerId, activeOwnerId)
        return entity.copy(ownerId = owner)
    }
}
