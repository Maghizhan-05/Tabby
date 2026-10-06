package com.maghizhan.tabby.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maghizhan.tabby.data.local.CategoryStore
import com.maghizhan.tabby.data.local.EntryWriter
import com.maghizhan.tabby.data.local.ExpenseDao
import com.maghizhan.tabby.data.local.ExpenseStore
import com.maghizhan.tabby.data.local.RoomTransactionRunner
import com.maghizhan.tabby.data.local.TabbyDatabase
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
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
 * Creating a category and saving the expense that uses it is ONE transaction.
 *
 * The defect: the entry view model saved the new category through one store call
 * and the expense through another, so a failure or cancellation between them
 * committed the category alone. That left a category the user never deliberately
 * created sitting in the picker, with nothing pending to sync and no way to tell
 * it apart from a real one. Either both land or neither does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntryWriterTest {

    private lateinit var database: TabbyDatabase
    private lateinit var writer: EntryWriter
    private val owner = "11111111-1111-1111-1111-111111111111"
    private val otherOwner = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TabbyDatabase::class.java
        ).allowMainThreadQueries().build()
        val runner = RoomTransactionRunner(database)
        writer = EntryWriter(
            ExpenseStore(database.expenseDao(), runner),
            CategoryStore(database.categoryDao(), runner),
            runner
        )
    }

    @After
    fun tearDown() = database.close()

    private fun expense(
        amount: BigDecimal = BigDecimal("10.00"),
        category: String = "Climbing",
        ownerId: String? = owner
    ) = ExpenseEntity(
        id = UUID.randomUUID(),
        amount = amount,
        categoryName = category,
        note = null,
        date = Instant.EPOCH,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        syncStateRaw = SyncState.LOCAL.raw,
        remoteId = null,
        ownerId = ownerId
    )

    private fun category(name: String = "Climbing") = CategoryEntity(
        id = UUID.randomUUID(),
        name = name,
        isDefault = false,
        sortOrder = 3,
        syncStateRaw = SyncState.LOCAL.raw,
        remoteId = null,
        ownerId = owner
    )

    @Test
    fun `both the new category and the expense are persisted`() = runTest {
        val newCategory = category()
        val stored = writer.save(expense(), newCategory, owner)

        assertNotNull(database.categoryDao().byIdUnscoped(newCategory.id))
        assertEquals(1, database.expenseDao().allForOwner(owner).size)
        assertEquals(owner, stored.ownerId)
        // The revision bump is what makes CAS acknowledgement work.
        assertEquals(1, stored.revision)
    }

    @Test
    fun `a failing expense save leaves no orphan category behind`() = runTest {
        val newCategory = category()

        // An over-scale amount is rejected by MoneyValidation inside the expense
        // store — exactly the kind of mid-sequence failure that used to commit
        // the category and nothing else.
        val failure = runCatching {
            writer.save(expense(amount = BigDecimal("10.005")), newCategory, owner)
        }

        assertTrue(
            failure.exceptionOrNull() is MoneyValidation.InvalidAmountException
        )
        assertNull(
            "the category must be rolled back with the failed expense",
            database.categoryDao().byIdUnscoped(newCategory.id)
        )
        assertTrue(database.expenseDao().allForOwner(owner).isEmpty())
    }

    @Test
    fun `a foreign-owned expense id refuses the whole write`() = runTest {
        val collidingId = UUID.randomUUID()
        database.expenseDao().upsert(
            listOf(expense(ownerId = otherOwner).copy(id = collidingId))
        )
        val newCategory = category()

        val failure = runCatching {
            writer.save(expense().copy(id = collidingId), newCategory, owner)
        }

        assertTrue(failure.isFailure)
        assertNull(
            "a refused expense must not leave its category behind",
            database.categoryDao().byIdUnscoped(newCategory.id)
        )
        assertEquals(otherOwner, database.expenseDao().byIdUnscoped(collidingId)?.ownerId)
    }

    @Test
    fun `an expense with no new category still saves`() = runTest {
        writer.save(expense(category = "Food"), null, owner)

        assertEquals(1, database.expenseDao().allForOwner(owner).size)
        assertEquals(0, database.categoryDao().countAll())
    }

    /** Guards the reentrancy assumption the writer depends on. */
    @Test
    fun `the nested store transactions join the outer one`() = runTest {
        val dao: ExpenseDao = database.expenseDao()
        writer.save(expense(), category(), owner)
        assertEquals(1, dao.allForOwner(owner).size)
    }
}
