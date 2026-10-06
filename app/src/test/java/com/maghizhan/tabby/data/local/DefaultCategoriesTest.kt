package com.maghizhan.tabby.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** First-run seeding of the default categories. */
@RunWith(RobolectricTestRunner::class)
// Pinned to 35 like the other Room suites: the default picker resolves to the
// compileSdk, for which no Robolectric android-all jar is published yet.
@Config(sdk = [35])
class DefaultCategoriesTest {

    private lateinit var database: TabbyDatabase
    private lateinit var dao: CategoryDao
    private lateinit var transactions: TransactionRunner
    private lateinit var marker: SeedMarker

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TabbyDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.categoryDao()
        transactions = RoomTransactionRunner(database)
        marker = InMemorySeedMarker()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun seed(using: SeedMarker = marker) =
        DefaultCategories.seedIfNeeded(dao, transactions, using)

    @Test
    fun `seeding an empty table inserts the eight defaults in order`() = runTest {
        seed()

        val seeded = dao.allUnscoped()
        assertEquals(DefaultCategories.NAMES, seeded.map { it.name })
        assertEquals((0..7).toList(), seeded.map { it.sortOrder })
    }

    @Test
    fun `seeded defaults carry no owner and are already SYNCED`() = runTest {
        seed()

        val seeded = dao.allUnscoped()
        // ownerId null: they exist before any account and are shared.
        assertTrue(seeded.all { it.ownerId == null })
        // SYNCED: presets must never be pushed as if they were user data.
        assertTrue(seeded.all { it.syncState == com.maghizhan.tabby.data.sync.SyncState.SYNCED })
        assertTrue(seeded.all { it.isDefault })
    }

    @Test
    fun `seeding twice does not duplicate`() = runTest {
        seed()
        seed()

        assertEquals(DefaultCategories.NAMES.size, dao.countAll())
    }

    @Test
    fun `seeding does not resurrect a default the user deleted`() = runTest {
        seed()
        val food = dao.allUnscoped().first { it.name == "Food" }
        // Hard-deleted, as a default has no remote counterpart to tombstone.
        dao.deleteDefaultById(food.id)

        seed()

        // The marker, not row presence, is what stops a re-seed — so a
        // deliberately removed default stays removed.
        val names = dao.allUnscoped().map { it.name }
        assertEquals(DefaultCategories.NAMES.size - 1, names.size)
        assertTrue("Food" !in names)
    }

    /**
     * The race Reviewer caught: seeding and the first sync run both started from
     * `onCreate`, and the old implementation skipped whenever ANY category row
     * existed. If a pull landed first, the eight defaults were skipped — and the
     * skip was permanent, because the condition never became true again. Which
     * one won was a coin flip on a user's device.
     */
    @Test
    fun `defaults are still seeded when sync pulled a category first`() = runTest {
        dao.upsert(
            listOf(
                com.maghizhan.tabby.data.local.entity.CategoryEntity(
                    id = UUID.randomUUID(),
                    name = "Imported",
                    isDefault = false,
                    sortOrder = 0,
                    syncStateRaw = com.maghizhan.tabby.data.sync.SyncState.SYNCED.raw,
                    remoteId = "remote-1",
                    ownerId = "owner-a"
                )
            )
        )

        seed()

        val names = dao.allUnscoped().map { it.name }
        assertTrue("the pulled category must survive", "Imported" in names)
        for (name in DefaultCategories.NAMES) {
            assertTrue("default '$name' was lost to the seeding race", name in names)
        }
        assertEquals(DefaultCategories.NAMES.size + 1, dao.countAll())
    }

    @Test
    fun `a default name the account already has is not duplicated`() = runTest {
        dao.upsert(
            listOf(
                com.maghizhan.tabby.data.local.entity.CategoryEntity(
                    id = UUID.randomUUID(),
                    name = "food",
                    isDefault = false,
                    sortOrder = 0,
                    syncStateRaw = com.maghizhan.tabby.data.sync.SyncState.SYNCED.raw,
                    remoteId = "remote-1",
                    ownerId = "owner-a"
                )
            )
        )

        seed()

        // Case-insensitive, because expenses reference categories by name and
        // "food"/"Food" would otherwise split one category into two everywhere.
        assertEquals(1, dao.allUnscoped().count { it.name.equals("food", ignoreCase = true) })
        assertEquals(DefaultCategories.NAMES.size, dao.countAll())
    }

    @Test
    fun `a marker already set skips seeding entirely`() = runTest {
        seed(using = InMemorySeedMarker(seeded = true))
        assertEquals(0, dao.countAll())
    }
}
