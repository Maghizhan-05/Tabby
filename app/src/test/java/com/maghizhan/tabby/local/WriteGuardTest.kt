package com.maghizhan.tabby.local

import com.maghizhan.tabby.data.local.WriteGuard
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.local.entity.FriendEntity
import com.maghizhan.tabby.data.sync.MoneyValidation
import com.maghizhan.tabby.data.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The write boundary.
 *
 * Owner predicates in SELECT/DELETE cannot protect an INSERT — an upsert is a
 * primary-key replacement with no WHERE clause, so handed a row belonging to
 * another account it would silently overwrite it. These pin the checks that make
 * that impossible, and the money normalization that stops an unrepresentable
 * value reaching a numeric(12,2) column.
 */
class WriteGuardTest {

    private val ownerA = "11111111-1111-1111-1111-111111111111"
    private val ownerB = "22222222-2222-2222-2222-222222222222"
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun expense(owner: String?, amount: String = "10.00") = ExpenseEntity(
        id = UUID.randomUUID(),
        amount = BigDecimal(amount),
        categoryName = "Coffee",
        note = null,
        date = t0,
        createdAt = t0,
        updatedAt = t0,
        syncStateRaw = SyncState.LOCAL.raw,
        remoteId = null,
        ownerId = owner,
        revision = 0
    )

    private fun friend(owner: String?, theyOwe: String = "5.00", weOwe: String = "0.00") = FriendEntity(
        id = UUID.randomUUID(),
        name = "Sam",
        theyOweUs = BigDecimal(theyOwe),
        weOweThem = BigDecimal(weOwe),
        createdAt = t0,
        updatedAt = t0,
        syncStateRaw = SyncState.LOCAL.raw,
        remoteId = null,
        ownerId = owner
    )

    @Test
    fun `a write for another account is refused`() {
        assertThrows(WriteGuard.ForeignOwnerException::class.java) {
            WriteGuard.authorizeExpense(expense(ownerB), ownerA)
        }
    }

    /** An unclaimed local row is claimed by the signed-in account. */
    @Test
    fun `an unowned row is claimed by the active owner`() {
        val authorized = WriteGuard.authorizeExpense(expense(null), ownerA)
        assertEquals(ownerA, authorized.ownerId)
    }

    @Test
    fun `an owner-matched row keeps its owner`() {
        val authorized = WriteGuard.authorizeExpense(expense(ownerA), ownerA)
        assertEquals(ownerA, authorized.ownerId)
    }

    /** Over-scale money is rejected at the write boundary, not silently rounded. */
    @Test
    fun `an over-scale amount is refused before it is persisted`() {
        assertThrows(MoneyValidation.InvalidAmountException::class.java) {
            WriteGuard.authorizeExpense(expense(ownerA, amount = "10.005"), ownerA)
        }
    }

    @Test
    fun `an out-of-range amount is refused before it is persisted`() {
        assertThrows(MoneyValidation.InvalidAmountException::class.java) {
            WriteGuard.authorizeExpense(expense(ownerA, amount = "10000000000.00"), ownerA)
        }
    }

    /** Scale is normalized so stored values match the backend column exactly. */
    @Test
    fun `an in-range amount is normalized to two decimals`() {
        val authorized = WriteGuard.authorizeExpense(expense(ownerA, amount = "10"), ownerA)
        assertEquals("10.00", authorized.amount.toPlainString())
    }

    @Test
    fun `a negative friend balance is refused`() {
        assertThrows(MoneyValidation.InvalidAmountException::class.java) {
            WriteGuard.authorizeFriend(friend(ownerA, theyOwe = "-1.00"), ownerA)
        }
    }

    @Test
    fun `a friend row for another account is refused`() {
        assertThrows(WriteGuard.ForeignOwnerException::class.java) {
            WriteGuard.authorizeFriend(friend(ownerB), ownerA)
        }
    }

    /**
     * Seeded defaults are shared and must NOT be claimed by whoever is signed
     * in, or they would start syncing and could be deleted for everyone.
     */
    @Test
    fun `a default category is passed through unclaimed`() {
        val default = CategoryEntity(
            id = UUID.randomUUID(),
            name = "Food",
            isDefault = true,
            sortOrder = 0,
            syncStateRaw = SyncState.SYNCED.raw,
            remoteId = null,
            ownerId = null
        )
        assertNull(WriteGuard.authorizeCategory(default, ownerA).ownerId)
    }

    @Test
    fun `a custom category for another account is refused`() {
        val foreign = CategoryEntity(
            id = UUID.randomUUID(),
            name = "Pets",
            isDefault = false,
            sortOrder = 1,
            syncStateRaw = SyncState.LOCAL.raw,
            remoteId = null,
            ownerId = ownerB
        )
        assertThrows(WriteGuard.ForeignOwnerException::class.java) {
            WriteGuard.authorizeCategory(foreign, ownerA)
        }
    }
}
