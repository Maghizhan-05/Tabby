package com.maghizhan.tabby.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maghizhan.tabby.data.local.RoomTransactionRunner
import com.maghizhan.tabby.data.local.TabbyDatabase
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.local.entity.FriendEntity
import com.maghizhan.tabby.data.remote.ActiveSession
import com.maghizhan.tabby.data.remote.CategoryRepositoring
import com.maghizhan.tabby.data.remote.CompleteSnapshot
import com.maghizhan.tabby.data.remote.ExpenseRepositoring
import com.maghizhan.tabby.data.remote.FriendRepositoring
import com.maghizhan.tabby.data.remote.SessionBinding
import com.maghizhan.tabby.data.remote.SessionProvider
import com.maghizhan.tabby.data.remote.model.RemoteCategoryRow
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.remote.model.RemoteFriendRow
import com.maghizhan.tabby.data.sync.CategorySyncCoordinator
import com.maghizhan.tabby.data.sync.ExpenseSyncCoordinator
import com.maghizhan.tabby.data.sync.FriendSyncCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * A remote row whose UUID collides with another account's cached row must not
 * overwrite it.
 *
 * The defect: reconciliation plans owner-filtered, but the applies land through
 * an id-only `@Upsert`, and an INSERT has no WHERE clause. A collision therefore
 * REPLACED the other account's row — the primary key matched, so SQLite happily
 * destroyed an expense, category or friend belonging to a different user. The
 * collision is now screened inside the same transaction as the apply, and the
 * colliding row is dropped rather than merged (it is not ours to write, and the
 * backend will re-send it for the account that does own it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconciliationCollisionTest {

    private lateinit var database: TabbyDatabase
    private val ownerA = "11111111-1111-1111-1111-111111111111"
    private val ownerB = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TabbyDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private class FixedSessions(private val session: ActiveSession?) : SessionProvider {
        override suspend fun current(): ActiveSession? = session
    }

    // MARK: - Expenses

    private class ExpenseRepo(private val rows: List<RemoteExpenseRow>) : ExpenseRepositoring {
        override suspend fun fetchAll(binding: SessionBinding) =
            CompleteSnapshot.forTesting(rows, binding.session)
        override suspend fun upsert(rows: List<RemoteExpenseRow>) = Unit
        override suspend fun delete(ids: List<UUID>) = Unit
    }

    @Test
    fun `a colliding remote expense does not overwrite another account's row`() = runTest {
        val collidingId = UUID.randomUUID()

        // Account B's row is already cached on this device.
        database.expenseDao().upsert(
            listOf(
                ExpenseEntity(
                    id = collidingId,
                    amount = BigDecimal("777.00"),
                    categoryName = "Rent",
                    note = "B's record",
                    date = Instant.EPOCH,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    syncStateRaw = com.maghizhan.tabby.data.sync.SyncState.SYNCED.raw,
                    remoteId = collidingId.toString(),
                    ownerId = ownerB,
                    revision = 3
                )
            )
        )

        // A's snapshot carries the SAME uuid.
        val outcome = ExpenseSyncCoordinator(
            dao = database.expenseDao(),
            repository = ExpenseRepo(
                listOf(
                    RemoteExpenseRow(
                        id = collidingId,
                        userId = ownerA,
                        amount = BigDecimal("1.00"),
                        categoryName = "Coffee",
                        note = null,
                        date = Instant.EPOCH,
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH
                    )
                )
            ),
            transactions = RoomTransactionRunner(database),
            sessions = FixedSessions(ActiveSession(ownerA, 1))
        ).synchronize()

        val surviving = database.expenseDao().byIdUnscoped(collidingId)
        assertEquals("another account's expense was overwritten", ownerB, surviving?.ownerId)
        assertEquals(BigDecimal("777.00"), surviving?.amount)
        assertEquals("B's record", surviving?.note)
        assertEquals(1, outcome.collisionsRefused)
        assertEquals(0, outcome.inserted)
    }

    @Test
    fun `a non-colliding remote expense is still applied`() = runTest {
        val id = UUID.randomUUID()
        val outcome = ExpenseSyncCoordinator(
            dao = database.expenseDao(),
            repository = ExpenseRepo(
                listOf(
                    RemoteExpenseRow(
                        id = id,
                        userId = ownerA,
                        amount = BigDecimal("12.00"),
                        categoryName = "Coffee",
                        note = null,
                        date = Instant.EPOCH,
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH
                    )
                )
            ),
            transactions = RoomTransactionRunner(database),
            sessions = FixedSessions(ActiveSession(ownerA, 1))
        ).synchronize()

        assertEquals(0, outcome.collisionsRefused)
        assertEquals(1, outcome.inserted)
        assertEquals(ownerA, database.expenseDao().byIdUnscoped(id)?.ownerId)
    }

    // MARK: - Categories

    private class CategoryRepo(private val rows: List<RemoteCategoryRow>) : CategoryRepositoring {
        override suspend fun fetchAll(binding: SessionBinding) =
            CompleteSnapshot.forTesting(rows, binding.session)
        override suspend fun upsert(rows: List<RemoteCategoryRow>) = Unit
        override suspend fun delete(ids: List<UUID>) = Unit
    }

    @Test
    fun `a colliding remote category does not overwrite another account's row`() = runTest {
        val collidingId = UUID.randomUUID()
        database.categoryDao().upsert(
            listOf(
                CategoryEntity(
                    id = collidingId,
                    name = "B's Category",
                    isDefault = false,
                    sortOrder = 9,
                    syncStateRaw = com.maghizhan.tabby.data.sync.SyncState.SYNCED.raw,
                    remoteId = collidingId.toString(),
                    ownerId = ownerB
                )
            )
        )

        val outcome = CategorySyncCoordinator(
            dao = database.categoryDao(),
            repository = CategoryRepo(
                listOf(
                    RemoteCategoryRow(
                        id = collidingId,
                        userId = ownerA,
                        name = "A's Category",
                        isDefault = false,
                        sortOrder = 0
                    )
                )
            ),
            transactions = RoomTransactionRunner(database),
            sessions = FixedSessions(ActiveSession(ownerA, 1))
        ).synchronize()

        val surviving = database.categoryDao().byIdUnscoped(collidingId)
        assertEquals(ownerB, surviving?.ownerId)
        assertEquals("B's Category", surviving?.name)
        assertEquals(1, outcome.collisionsRefused)
    }

    @Test
    fun `a colliding remote category cannot replace a shared seeded default`() = runTest {
        val defaultId = UUID.randomUUID()
        database.categoryDao().upsert(
            listOf(
                CategoryEntity(
                    id = defaultId,
                    name = "Food",
                    isDefault = true,
                    sortOrder = 0,
                    syncStateRaw = com.maghizhan.tabby.data.sync.SyncState.SYNCED.raw,
                    remoteId = null,
                    ownerId = null
                )
            )
        )

        val outcome = CategorySyncCoordinator(
            dao = database.categoryDao(),
            repository = CategoryRepo(
                listOf(
                    RemoteCategoryRow(
                        id = defaultId,
                        userId = ownerA,
                        name = "Hijacked",
                        isDefault = false,
                        sortOrder = 5
                    )
                )
            ),
            transactions = RoomTransactionRunner(database),
            sessions = FixedSessions(ActiveSession(ownerA, 1))
        ).synchronize()

        val surviving = database.categoryDao().byIdUnscoped(defaultId)
        assertTrue("a shared default must stay shared", surviving?.isDefault == true)
        assertEquals("Food", surviving?.name)
        assertEquals(1, outcome.collisionsRefused)
    }

    // MARK: - Friends

    private class FriendRepo(private val rows: List<RemoteFriendRow>) : FriendRepositoring {
        override suspend fun fetchAll(binding: SessionBinding) =
            CompleteSnapshot.forTesting(rows, binding.session)
        override suspend fun upsert(rows: List<RemoteFriendRow>) = Unit
        override suspend fun delete(ids: List<UUID>) = Unit
    }

    @Test
    fun `a colliding remote friend does not overwrite another account's row`() = runTest {
        val collidingId = UUID.randomUUID()
        database.friendDao().upsert(
            listOf(
                FriendEntity(
                    id = collidingId,
                    name = "B's friend",
                    ownerId = ownerB,
                    theyOweUs = BigDecimal("50.00"),
                    weOweThem = BigDecimal.ZERO,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    syncStateRaw = com.maghizhan.tabby.data.sync.SyncState.SYNCED.raw,
                    remoteId = collidingId.toString()
                )
            )
        )

        val outcome = FriendSyncCoordinator(
            dao = database.friendDao(),
            repository = FriendRepo(
                listOf(
                    RemoteFriendRow(
                        id = collidingId,
                        userId = ownerA,
                        name = "A's friend",
                        theyOweUs = BigDecimal.ZERO,
                        weOweThem = BigDecimal("1.00"),
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH
                    )
                )
            ),
            transactions = RoomTransactionRunner(database),
            sessions = FixedSessions(ActiveSession(ownerA, 1))
        ).synchronize()

        val surviving = database.friendDao().byIdUnscoped(collidingId)
        assertEquals(ownerB, surviving?.ownerId)
        assertEquals("B's friend", surviving?.name)
        assertEquals(BigDecimal("50.00"), surviving?.theyOweUs)
        assertEquals(1, outcome.collisionsRefused)
    }
}
