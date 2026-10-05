package com.maghizhan.tabby.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.local.entity.FriendEntity
import com.maghizhan.tabby.data.local.entity.UserProfileEntity
import com.maghizhan.tabby.data.sync.SyncState
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * DAOs.
 *
 * Every read is owner-scoped and excludes DELETED tombstones, mirroring the iOS
 * `ExpenseOwnership.visibleExpenses` rule: a tombstone is pending remote
 * deletion and must stay hidden, and another account's rows are never visible.
 */
@Dao
interface ExpenseDao {

    @Query(
        """
        SELECT * FROM expenses
        WHERE syncStateRaw != :deletedState
          AND (ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId))
        ORDER BY date DESC
        """
    )
    fun observeVisible(ownerId: String, deletedState: Int = SyncState.DELETED.raw): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun byId(id: UUID): ExpenseEntity?

    @Query(
        """
        SELECT * FROM expenses
        WHERE ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun allForOwner(ownerId: String): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE syncStateRaw != :syncedState")
    suspend fun pendingPush(syncedState: Int = SyncState.SYNCED.raw): List<ExpenseEntity>

    @Upsert
    suspend fun upsert(expenses: List<ExpenseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ExpenseEntity)

    @Delete
    suspend fun delete(expense: ExpenseEntity)

    @Query("DELETE FROM expenses WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<UUID>)
}

@Dao
interface CategoryDao {

    @Query(
        """
        SELECT * FROM categories
        WHERE isDefault = 1 OR ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId)
        ORDER BY sortOrder ASC, name ASC
        """
    )
    fun observeForOwner(ownerId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun all(): List<CategoryEntity>

    @Upsert
    suspend fun upsert(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<UUID>)
}

@Dao
interface FriendDao {

    @Query(
        """
        SELECT * FROM friends
        WHERE syncStateRaw != :deletedState
          AND (ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId))
        ORDER BY name ASC
        """
    )
    fun observeVisible(ownerId: String, deletedState: Int = SyncState.DELETED.raw): Flow<List<FriendEntity>>

    @Query(
        """
        SELECT * FROM friends
        WHERE ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun allForOwner(ownerId: String): List<FriendEntity>

    @Upsert
    suspend fun upsert(friends: List<FriendEntity>)

    @Query("DELETE FROM friends WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<UUID>)
}

@Dao
interface UserProfileDao {

    @Query("SELECT * FROM user_profiles WHERE LOWER(ownerId) = LOWER(:ownerId) LIMIT 1")
    suspend fun forOwner(ownerId: String): UserProfileEntity?

    @Upsert
    suspend fun upsert(profile: UserProfileEntity)
}
