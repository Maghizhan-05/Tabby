package com.maghizhan.tabby.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.local.entity.FriendEntity
import com.maghizhan.tabby.data.local.entity.UserProfileEntity

/**
 * The single Room database.
 *
 * Unlike iOS there is no App Group to configure: the Glance widget runs in the
 * same process as the app, so it reads this very database. That removes the
 * whole class of widget-desync bug the iOS side had to guard against — but it
 * also means the widget must be refreshed after every write (see the sync
 * engine's update hook), not merely assume shared storage is enough.
 */
@Database(
    entities = [
        ExpenseEntity::class,
        CategoryEntity::class,
        FriendEntity::class,
        UserProfileEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class TabbyDatabase : RoomDatabase() {

    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun friendDao(): FriendDao
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        private const val NAME = "tabby.db"

        @Volatile
        private var instance: TabbyDatabase? = null

        fun get(context: Context): TabbyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TabbyDatabase::class.java,
                    NAME
                )
                    // Explicit migrations, and deliberately NO
                    // fallbackToDestructiveMigration: this database holds the
                    // user's financial records, and rows that have not been
                    // pushed yet exist nowhere else. A missing migration must
                    // fail loudly in development rather than quietly wipe data
                    // on a user's device.
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
    }
}
