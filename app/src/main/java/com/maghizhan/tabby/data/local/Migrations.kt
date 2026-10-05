package com.maghizhan.tabby.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations.
 *
 * v1 -> v2 covers two corrections made after v1 was already created on devices
 * by the previous checkpoint's build. Replacing the checked-in v1 schema JSON
 * does NOT migrate an existing database, so without this an installed app hits
 * Room's "schema changed" crash on launch — or, worse with destructive
 * fallback, silently loses the user's expenses.
 *
 * 1. **Timestamps were stored as epoch milliseconds and are now microseconds.**
 *    Every `INTEGER` time column is multiplied by 1000. This is lossless in the
 *    direction that matters: the extra precision simply did not exist before, so
 *    the migrated value is the same instant with a zero microsecond remainder.
 *    Leaving the values unscaled would make every existing row read as 1970,
 *    and last-writer-wins would then let any remote row overwrite local data.
 *
 * 2. **`user_profiles` matches the shared contract.** The old table keyed a
 *    local UUID with `ownerId`/`currencyCode`/`updatedAt`; the real contract is
 *    `id == auth.uid` plus `email` and the backend's `createdAt`. The table is
 *    rebuilt rather than altered because its primary key changes type, and
 *    SQLite cannot alter a primary key in place.
 *
 *    Existing profile rows are NOT carried over: the old `id` was a local UUID,
 *    not the account id, so there is no correct value to migrate it to and
 *    inventing one would attach a profile to the wrong account. The row is
 *    re-fetched from the backend on the next sync, which is lossless because the
 *    backend is the source of truth for profiles. Expenses, categories and
 *    friends — the data the user would actually miss — are preserved.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Milliseconds -> microseconds.
        db.execSQL("UPDATE expenses SET date = date * 1000, createdAt = createdAt * 1000, updatedAt = updatedAt * 1000")
        db.execSQL("UPDATE friends SET createdAt = createdAt * 1000, updatedAt = updatedAt * 1000")

        // 2. Rebuild user_profiles to the shared contract.
        db.execSQL("DROP TABLE IF EXISTS user_profiles")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_profiles (
                id TEXT NOT NULL,
                email TEXT NOT NULL,
                displayName TEXT,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
    }
}
