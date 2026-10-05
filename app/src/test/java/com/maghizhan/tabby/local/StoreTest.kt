package com.maghizhan.tabby.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maghizhan.tabby.data.local.CategoryStore
import com.maghizhan.tabby.data.local.ExpenseStore
import com.maghizhan.tabby.data.local.ForeignOwnerException
import com.maghizhan.tabby.data.local.FriendStore
import com.maghizhan.tabby.data.local.RoomTransactionRunner
import com.maghizhan.tabby.data.local.TabbyDatabase
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.local.entity.FriendEntity
import com.maghizhan.tabby.data.sync.MoneyValidation
import com.maghizhan.tabby.data.sync.SyncState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Store behaviour against a REAL Room database.
 *
 * These replace the previous guard tests, which only exercised a pure validator
 * and so could not catch the actual hole: an upsert replacing a row owned by
 * another account. A primary-key replacement has no WHERE clause, so only
 * reading the existing row inside the same transaction stops it — and that is
 * only testable with a real database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreTest {

    private lateinit var database: TabbyDatabase
    private lateinit var expenses: ExpenseStore
    private lateinit var categories: CategoryStore
    private lateinit var friends: FriendStore

    private val ownerA = "11111111-1111-1111-1111-111111111111"
    private val ownerB = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TabbyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val runner = RoomTransactionRunner(database)
        expenses = ExpenseStore(database.expenseDao(), runner)
        categories = CategoryStore(database.categoryDao(), runner)
        friends = FriendStore(database.friendDao(), runner)
    }

    @After
    fun tearDown() = database.close()

    private fun expense(
        id: UUID = UUID.randomUUID(),
        amount: BigDecimal = BigDecimal("10.00"),
        owner: String? = ownerA,
        state: SyncState = SyncState.LOCAL,
        revision: Int = 0
    ) = ExpenseEntity(
        id = id,
        amount = amount,
        categoryName = "Coffee",
        note = null,
        date = Instant.parse("2026-01-01T00:00:00Z"),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        syncStateRaw = state.raw,
        remoteId = null,
        ownerId = owner,
        revision = revision
    )

    @Test
    fun `save stamps the active owner and bumps the revision`() = runTest {
        val stored = expenses.save(expense(owner = null), ownerA)

        assertEquals(ownerA, stored.ownerId)
        // The bump is what makes CAS acknowledgement work.
        assertEquals(1, stored.revision)
        assertEquals(1, database.expenseDao().allForOwner(ownerA).size)
    }

    @Test
    fun `save refuses to overwrite a row owned by another account`() = runTest {
        val id = UUID.randomUUID()
        expenses.save(expense(id = id), ownerA)

        // Same UUID, different account: without the existing-owner check this
        // upsert would REPLACE account A's row.
        val failure = runCatching {
            expenses.save(expense(id = id, amount = BigDecimal("999.00")), ownerB)
        }
        assertTrue(
            "a foreign-owned row must not be overwritten",
            failure.exceptionOrNull() is ForeignOwnerException
        )

        val surviving = database.expenseDao().byIdUnscoped(id)
        assertEquals(ownerA, surviving?.ownerId)
        assertEquals(BigDecimal("10.00"), surviving?.amount)
    }

    @Test
    fun `save rejects an over-scale amount at the local write boundary`() = runTest {
        val failure = runCatching { expenses.save(expense(amount = BigDecimal("10.005")), ownerA) }
        assertTrue(
            "over-scale must be rejected at the write boundary",
            failure.exceptionOrNull() is MoneyValidation.InvalidAmountException
        )
        assertTrue(database.expenseDao().allForOwner(ownerA).isEmpty())
    }

    @Test
    fun `save rejects an out-of-range amount at the local write boundary`() = runTest {
        val failure = runCatching { expenses.save(expense(amount = BigDecimal("100000000000.00")), ownerA) }
        assertTrue(
            "out-of-range must be rejected at the write boundary",
            failure.exceptionOrNull() is MoneyValidation.InvalidAmountException
        )
        assertTrue(database.expenseDao().allForOwner(ownerA).isEmpty())
    }

    @Test
    fun `save rejects a negative friend balance at the local write boundary`() = runTest {
        val friend = FriendEntity(
            id = UUID.randomUUID(),
            name = "Sam",
            ownerId = ownerA,
            theyOweUs = BigDecimal("-1.00"),
            weOweThem = BigDecimal.ZERO,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            syncStateRaw = SyncState.LOCAL.raw,
            remoteId = null,
            revision = 0
        )
        val failure = runCatching { friends.save(friend, ownerA) }
        assertTrue(
            "negative balance must be rejected at the write boundary",
            failure.exceptionOrNull() is MoneyValidation.InvalidAmountException
        )
        assertTrue(database.friendDao().allForOwner(ownerA).isEmpty())
    }

    @Test
    fun `markDeleted writes a tombstone rather than removing the row`() = runTest {
        val stored = expenses.save(expense(), ownerA)
        expenses.markDeleted(stored, ownerA)

        val row = database.expenseDao().byIdUnscoped(stored.id)
        assertNotNull("tombstone must survive until the backend is told", row)
        assertEquals(SyncState.DELETED, row?.syncState)
    }

    @Test
    fun `a seeded default category is never claimed by the signed-in account`() = runTest {
        val seeded = CategoryEntity(
            id = UUID.randomUUID(),
            name = "Groceries",
            isDefault = true,
            sortOrder = 0,
            syncStateRaw = SyncState.SYNCED.raw,
            remoteId = null,
            ownerId = null,
            revision = 0
        )
        val stored = categories.save(seeded, ownerA)

        // Claiming it would start syncing a row shared with every other account.
        assertNull(stored.ownerId)
    }
}

/**
 * The real transaction actually rolls back.
 *
 * The previous version of this test used a pass-through runner and threw before
 * any mutation, so it would have passed even with no transaction at all. Here a
 * genuine [RoomTransactionRunner] performs a real insert and THEN fails, which
 * is the only arrangement that can observe a rollback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransactionRollbackTest {

    private lateinit var database: TabbyDatabase
    private val owner = "11111111-1111-1111-1111-111111111111"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TabbyDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private fun row(id: UUID) = ExpenseEntity(
        id = id,
        amount = BigDecimal("5.00"),
        categoryName = "Coffee",
        note = null,
        date = Instant.EPOCH,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        syncStateRaw = SyncState.SYNCED.raw,
        remoteId = id.toString(),
        ownerId = owner,
        revision = 0
    )

    @Test
    fun `a failure after the first mutation rolls the real transaction back`() = runTest {
        val runner = RoomTransactionRunner(database)
        val first = UUID.randomUUID()

        val failure = runCatching {
            runner.inTransaction {
                // A real mutation lands inside the transaction...
                database.expenseDao().upsert(listOf(row(first)))
                check(database.expenseDao().allForOwner(owner).size == 1)
                // ...and then the block fails.
                throw IllegalStateException("remote apply failed")
            }
        }

        assertTrue(failure.isFailure)
        assertTrue(
            "the inserted row must not survive a failed transaction",
            database.expenseDao().allForOwner(owner).isEmpty()
        )
    }

    @Test
    fun `a successful transaction commits its mutations`() = runTest {
        val runner = RoomTransactionRunner(database)
        runner.inTransaction { database.expenseDao().upsert(listOf(row(UUID.randomUUID()))) }
        assertEquals(1, database.expenseDao().allForOwner(owner).size)
    }
}
