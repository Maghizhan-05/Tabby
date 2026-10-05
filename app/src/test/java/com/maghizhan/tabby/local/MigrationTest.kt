package com.maghizhan.tabby.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.maghizhan.tabby.data.local.MIGRATION_1_2
import com.maghizhan.tabby.data.local.TabbyDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The v1 -> v2 migration, run against a real v1 database file.
 *
 * This is the test that actually matters for shipped data: replacing the
 * checked-in v1 schema JSON does nothing to a database already created on a
 * device by the previous checkpoint's build. Without a migration, an installed
 * app either crashes on launch with Room's "schema changed" error or — with
 * destructive fallback — silently loses the user's expenses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TabbyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private val databaseName = "migration-test.db"

    /**
     * Milliseconds must become microseconds.
     *
     * Leaving them unscaled would make every pre-existing row read as 1970, and
     * last-writer-wins would then let any remote row silently overwrite the
     * user's local data.
     */
    @Test
    fun `v1 millisecond timestamps are rescaled to microseconds`() {
        val id = UUID.randomUUID().toString()
        val millis = 1_767_225_600_000L // 2026-01-01T00:00:00Z

        helper.createDatabase(databaseName, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO expenses
                    (id, amount, categoryName, note, date, createdAt, updatedAt,
                     syncStateRaw, remoteId, ownerId, revision)
                VALUES ('$id', '12.34', 'Coffee', NULL, $millis, $millis, $millis, 0, NULL, 'owner-a', 0)
                """.trimIndent()
            )
        }

        val migrated = helper.runMigrationsAndValidate(databaseName, 2, true, MIGRATION_1_2)

        migrated.query("SELECT date, createdAt, updatedAt FROM expenses WHERE id = '$id'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(millis * 1000, cursor.getLong(0))
            assertEquals(millis * 1000, cursor.getLong(1))
            assertEquals(millis * 1000, cursor.getLong(2))
        }
    }

    /** The user's financial rows must survive the migration. */
    @Test
    fun `expense rows are preserved across the migration`() {
        val id = UUID.randomUUID().toString()

        helper.createDatabase(databaseName, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO expenses
                    (id, amount, categoryName, note, date, createdAt, updatedAt,
                     syncStateRaw, remoteId, ownerId, revision)
                VALUES ('$id', '99.99', 'Rent', 'note', 1000, 1000, 1000, 0, NULL, 'owner-a', 0)
                """.trimIndent()
            )
        }

        val migrated = helper.runMigrationsAndValidate(databaseName, 2, true, MIGRATION_1_2)

        migrated.query("SELECT amount, categoryName, note FROM expenses WHERE id = '$id'").use { cursor ->
            assertTrue("the user's expense was lost by the migration", cursor.moveToFirst())
            assertEquals("99.99", cursor.getString(0))
            assertEquals("Rent", cursor.getString(1))
            assertEquals("note", cursor.getString(2))
        }
    }

    /**
     * `user_profiles` is rebuilt to the shared contract (`id == auth.uid`).
     * `runMigrationsAndValidate` is what proves the migrated schema matches the
     * entities — a mismatch fails here rather than on a user's device.
     */
    @Test
    fun `the migrated schema validates against the v2 entities`() {
        helper.createDatabase(databaseName, 1).close()
        val migrated = helper.runMigrationsAndValidate(databaseName, 2, true, MIGRATION_1_2)

        migrated.query("SELECT name FROM pragma_table_info('user_profiles')").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) columns += cursor.getString(0)
            assertEquals(setOf("id", "email", "displayName", "createdAt"), columns)
        }
    }

    /** Opening the real builder must not need a destructive fallback. */
    @Test
    fun `a v1 database opens at v2 through the production builder`() {
        helper.createDatabase(databaseName, 1).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, TabbyDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        // Forces the open; throws if the migration is missing or wrong.
        assertTrue(database.openHelper.writableDatabase.isOpen)
        assertEquals(2, database.openHelper.writableDatabase.version)
        database.close()
    }
}
