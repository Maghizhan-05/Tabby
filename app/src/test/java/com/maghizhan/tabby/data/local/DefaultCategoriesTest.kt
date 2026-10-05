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

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TabbyDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.categoryDao()
        transactions = RoomTransactionRunner(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `seeding an empty table inserts the eight defaults in order`() = runTest {
        DefaultCategories.seedIfNeeded(dao, transactions)

        val seeded = dao.allUnscoped()
        assertEquals(DefaultCategories.NAMES, seeded.map { it.name })
        assertEquals((0..7).toList(), seeded.map { it.sortOrder })
    }

    @Test
    fun `seeded defaults carry no owner and are already SYNCED`() = runTest {
        DefaultCategories.seedIfNeeded(dao, transactions)

        val seeded = dao.allUnscoped()
        // ownerId null: they exist before any account and are shared.
        assertTrue(seeded.all { it.ownerId == null })
        // SYNCED: presets must never be pushed as if they were user data.
        assertTrue(seeded.all { it.syncState == com.maghizhan.tabby.data.sync.SyncState.SYNCED })
        assertTrue(seeded.all { it.isDefault })
    }

    @Test
    fun `seeding twice does not duplicate`() = runTest {
        DefaultCategories.seedIfNeeded(dao, transactions)
        DefaultCategories.seedIfNeeded(dao, transactions)

        assertEquals(DefaultCategories.NAMES.size, dao.countAll())
    }

    @Test
    fun `seeding does not resurrect a default the user deleted`() = runTest {
        DefaultCategories.seedIfNeeded(dao, transactions)
        val food = dao.allUnscoped().first { it.name == "Food" }
        // Hard-deleted, as a default has no remote counterpart to tombstone.
        dao.deleteDefaultById(food.id)

        DefaultCategories.seedIfNeeded(dao, transactions)

        // Emptiness, not name-presence, is the seeding test — otherwise a
        // deliberately removed default would reappear on every launch.
        val names = dao.allUnscoped().map { it.name }
        assertEquals(DefaultCategories.NAMES.size - 1, names.size)
        assertTrue("Food" !in names)
    }

    @Test
    fun `seeding is skipped when any category already exists`() = runTest {
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

        DefaultCategories.seedIfNeeded(dao, transactions)

        // A pulled account already has its categories; seeding on top would add
        // eight unwanted rows to an existing account.
        assertEquals(1, dao.countAll())
    }
}
