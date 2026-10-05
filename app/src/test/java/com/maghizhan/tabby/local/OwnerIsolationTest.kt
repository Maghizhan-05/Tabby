package com.maghizhan.tabby.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maghizhan.tabby.data.local.TabbyDatabase
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.local.entity.FriendEntity
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
import kotlinx.coroutines.flow.first

/**
 * Owner isolation, tested against a real in-memory SQLite database rather than a
 * fake, so a wrong SQL predicate cannot pass by agreeing with a wrong fake.
 *
 * Why this matters: the local database can hold rows for more than one account —
 * signing out leaves the previous account's cache behind. An unscoped query
 * would then read, upload, or delete someone else's financial records.
 *
 * Pinned to SDK 35: Robolectric 4.14.1 ships no API 36 runtime yet, and the SQL
 * and converter behaviour under test is not version-specific.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OwnerIsolationTest {

    private lateinit var db: TabbyDatabase

    private val ownerA = "11111111-aaaa-0000-0000-000000000001"
    private val ownerB = "22222222-bbbb-0000-0000-000000000002"
    private val t0 = Instant.parse("2026-01-02T03:04:05.123456Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TabbyDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun expense(
        id: UUID = UUID.randomUUID(),
        owner: String?,
        state: SyncState = SyncState.SYNCED,
        amount: String = "10.00"
    ) = ExpenseEntity(
        id = id,
        amount = BigDecimal(amount),
        categoryName = "Food",
        note = null,
        date = t0,
        createdAt = t0,
        updatedAt = t0,
        ownerId = owner,
        syncStateRaw = state.raw,
        remoteId = if (state == SyncState.SYNCED) id.toString() else null
    )

    private fun friend(id: UUID = UUID.randomUUID(), owner: String?, state: SyncState = SyncState.SYNCED) =
        FriendEntity(
            id = id,
            name = "Sam",
            ownerId = owner,
            theyOweUs = BigDecimal("5.00"),
            weOweThem = BigDecimal("0.00"),
            createdAt = t0,
            updatedAt = t0,
            syncStateRaw = state.raw
        )

    private fun category(
        id: UUID = UUID.randomUUID(),
        owner: String?,
        isDefault: Boolean = false,
        state: SyncState = SyncState.SYNCED
    ) = CategoryEntity(
        id = id,
        name = "Coffee",
        isDefault = isDefault,
        sortOrder = 0,
        syncStateRaw = state.raw,
        remoteId = null,
        ownerId = owner
    )

    // MARK: - Reads

    @Test
    fun `another account's expense is never visible`() = runTest {
        db.expenseDao().upsert(listOf(expense(owner = ownerA), expense(owner = ownerB)))

        val visible = db.expenseDao().observeVisible(ownerA).first()

        assertEquals(1, visible.size)
        assertEquals(ownerA, visible.single().ownerId)
    }

    @Test
    fun `an id lookup cannot reach another account's expense`() = runTest {
        // A bare `WHERE id = :id` would return this row to the wrong account.
        val foreign = UUID.randomUUID()
        db.expenseDao().upsert(listOf(expense(id = foreign, owner = ownerB)))

        assertNull(db.expenseDao().byId(foreign, ownerA))
        assertNotNull(db.expenseDao().byId(foreign, ownerB))
    }

    @Test
    fun `an id lookup cannot reach another account's friend`() = runTest {
        val foreign = UUID.randomUUID()
        db.friendDao().upsert(listOf(friend(id = foreign, owner = ownerB)))

        assertNull(db.friendDao().byId(foreign, ownerA))
        assertNotNull(db.friendDao().byId(foreign, ownerB))
    }

    /**
     * An unclaimed row is now invisible until a cycle claims it.
     *
     * This replaces the old rule that `ownerId IS NULL` was readable by whoever
     * asked. That made a legacy row visible to EVERY account, so a second user on
     * the device could see and sync the first user's pre-sign-in expenses. The
     * row is adopted by `claimLegacyRows` at the start of a cycle instead, which
     * is a single atomic statement and therefore cannot be raced.
     */
    @Test
    fun `an unclaimed row is invisible until it is claimed`() = runTest {
        db.expenseDao().upsert(listOf(expense(owner = null)))
        assertTrue(db.expenseDao().observeVisible(ownerA).first().isEmpty())

        assertEquals(1, db.expenseDao().claimLegacyRows(ownerA))
        assertEquals(1, db.expenseDao().observeVisible(ownerA).first().size)

        // ...and only for the account that claimed it.
        assertTrue(db.expenseDao().observeVisible(ownerB).first().isEmpty())
    }

    // MARK: - Tombstones

    @Test
    fun `a deleted expense is hidden`() = runTest {
        db.expenseDao().upsert(listOf(expense(owner = ownerA, state = SyncState.DELETED)))
        assertTrue(db.expenseDao().observeVisible(ownerA).first().isEmpty())
    }

    @Test
    fun `a deleted friend is hidden`() = runTest {
        db.friendDao().upsert(listOf(friend(owner = ownerA, state = SyncState.DELETED)))
        assertTrue(db.friendDao().observeVisible(ownerA).first().isEmpty())
    }

    @Test
    fun `a deleted category is hidden, as on iOS`() = runTest {
        // This was the gap: categories had no tombstone predicate, so a category
        // the user deleted kept appearing in the picker until the sync landed.
        db.categoryDao().upsert(listOf(category(owner = ownerA, state = SyncState.DELETED)))
        assertTrue(db.categoryDao().observeForOwner(ownerA).first().isEmpty())
    }

    @Test
    fun `another account's category is not visible but shared defaults are`() = runTest {
        db.categoryDao().upsert(
            listOf(
                category(owner = ownerB),
                category(owner = null, isDefault = true)
            )
        )

        val visible = db.categoryDao().observeForOwner(ownerA).first()

        assertEquals(1, visible.size)
        assertTrue(visible.single().isDefault)
    }

    // MARK: - Push scoping

    @Test
    fun `pendingPush never offers another account's unsynced rows for upload`() = runTest {
        // Unscoped, this would upload owner B's expense under owner A's user_id.
        db.expenseDao().upsert(
            listOf(
                expense(owner = ownerA, state = SyncState.LOCAL),
                expense(owner = ownerB, state = SyncState.LOCAL)
            )
        )

        val pending = db.expenseDao().pendingPush(ownerA)

        assertEquals(1, pending.size)
        assertEquals(ownerA, pending.single().ownerId)
    }

    @Test
    fun `pendingPush never offers another account's friends`() = runTest {
        db.friendDao().upsert(
            listOf(
                friend(owner = ownerA, state = SyncState.LOCAL),
                friend(owner = ownerB, state = SyncState.LOCAL)
            )
        )
        assertEquals(1, db.friendDao().pendingPush(ownerA).size)
    }

    @Test
    fun `pendingPush never offers shared default categories`() = runTest {
        // Defaults are not the user's to upload.
        db.categoryDao().upsert(
            listOf(
                category(owner = null, isDefault = true, state = SyncState.LOCAL),
                category(owner = ownerA, state = SyncState.LOCAL)
            )
        )

        val pending = db.categoryDao().pendingPush(ownerA)

        assertEquals(1, pending.size)
        assertEquals(ownerA, pending.single().ownerId)
    }

    // MARK: - Deletes

    @Test
    fun `deleteByIds cannot delete another account's expense`() = runTest {
        // The highest-severity case: an id alone must not authorise a delete.
        val foreign = UUID.randomUUID()
        db.expenseDao().upsert(listOf(expense(id = foreign, owner = ownerB)))

        db.expenseDao().deleteByIds(listOf(foreign), ownerA)

        assertNotNull("owner A deleted owner B's expense", db.expenseDao().byId(foreign, ownerB))
    }

    @Test
    fun `deleteByIds deletes the caller's own expense`() = runTest {
        val own = UUID.randomUUID()
        db.expenseDao().upsert(listOf(expense(id = own, owner = ownerA)))

        db.expenseDao().deleteByIds(listOf(own), ownerA)

        assertNull(db.expenseDao().byId(own, ownerA))
    }

    @Test
    fun `deleteByIds cannot delete another account's friend`() = runTest {
        val foreign = UUID.randomUUID()
        db.friendDao().upsert(listOf(friend(id = foreign, owner = ownerB)))

        db.friendDao().deleteByIds(listOf(foreign), ownerA)

        assertNotNull(db.friendDao().byId(foreign, ownerB))
    }

    @Test
    fun `deleteByIds never removes a shared default category`() = runTest {
        val shared = UUID.randomUUID()
        db.categoryDao().upsert(listOf(category(id = shared, owner = null, isDefault = true)))

        db.categoryDao().deleteByIds(listOf(shared), ownerA)

        assertEquals(1, db.categoryDao().allForOwner(ownerA).size)
    }

    // MARK: - Persistence precision

    @Test
    fun `microsecond timestamps and exact money survive a real database round trip`() = runTest {
        // End-to-end proof through actual SQLite, not just the converter.
        val id = UUID.randomUUID()
        db.expenseDao().upsert(listOf(expense(id = id, owner = ownerA, amount = "0.10")))

        val loaded = db.expenseDao().byId(id, ownerA)!!

        assertEquals(t0, loaded.updatedAt)
        assertEquals(123_456_000, loaded.updatedAt.nano)
        assertEquals(BigDecimal("0.10"), loaded.amount)
    }
}
