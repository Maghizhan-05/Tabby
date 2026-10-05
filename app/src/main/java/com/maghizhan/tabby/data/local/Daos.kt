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
 * Two invariants hold for EVERY account-sensitive query, reads and deletes
 * alike, mirroring the iOS `ExpenseOwnership.visibleExpenses` rule:
 *
 * 1. **Owner scoping.** Every statement requires the active owner id. The local
 *    database can hold rows for more than one account (a sign-out leaves the
 *    previous account's cache behind), so an unscoped `WHERE id = :id` would
 *    read, upload or delete another account's record. `ownerId IS NULL` is
 *    matched deliberately and only so pre-sign-in rows can still be claimed by
 *    the first account that signs in — see `Ownership.claimed`.
 *
 * 2. **Tombstones are invisible.** A DELETED row is pending remote deletion and
 *    must not appear in any list, for any entity type.
 *
 * Deletes are id-scoped AND owner-scoped: an id alone is attacker- or
 * bug-controlled, and `DELETE ... WHERE id IN (:ids)` would happily erase a row
 * belonging to someone else.
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

    /** Owner-scoped: a bare id lookup could surface another account's expense. */
    @Query(
        """
        SELECT * FROM expenses
        WHERE id = :id
          AND (ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId))
        """
    )
    suspend fun byId(id: UUID, ownerId: String): ExpenseEntity?

    @Query(
        """
        SELECT * FROM expenses
        WHERE ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun allForOwner(ownerId: String): List<ExpenseEntity>

    /**
     * Owner-scoped: an unscoped version would upload another account's cached
     * rows under the active session's user_id.
     */
    @Query(
        """
        SELECT * FROM expenses
        WHERE syncStateRaw != :syncedState
          AND (ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId))
        """
    )
    suspend fun pendingPush(ownerId: String, syncedState: Int = SyncState.SYNCED.raw): List<ExpenseEntity>

    @Upsert
    suspend fun upsert(expenses: List<ExpenseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ExpenseEntity)

    @Delete
    suspend fun delete(expense: ExpenseEntity)

    @Query(
        """
        DELETE FROM expenses
        WHERE id IN (:ids)
          AND (ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId))
        """
    )
    suspend fun deleteByIds(ids: List<UUID>, ownerId: String)
}

@Dao
interface CategoryDao {

    /**
     * Default categories are shared (no owner), so they are always visible;
     * user categories are owner-scoped. DELETED tombstones are excluded here
     * just as for expenses and friends — a category the user deleted must not
     * reappear in the picker while its deletion is still pending.
     */
    @Query(
        """
        SELECT * FROM categories
        WHERE syncStateRaw != :deletedState
          AND (isDefault = 1 OR ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId))
        ORDER BY sortOrder ASC, name ASC
        """
    )
    fun observeForOwner(ownerId: String, deletedState: Int = SyncState.DELETED.raw): Flow<List<CategoryEntity>>

    @Query(
        """
        SELECT * FROM categories
        WHERE isDefault = 1 OR ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun allForOwner(ownerId: String): List<CategoryEntity>

    @Query(
        """
        SELECT * FROM categories
        WHERE syncStateRaw != :syncedState
          AND isDefault = 0
          AND (ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId))
        """
    )
    suspend fun pendingPush(ownerId: String, syncedState: Int = SyncState.SYNCED.raw): List<CategoryEntity>

    @Upsert
    suspend fun upsert(categories: List<CategoryEntity>)

    /** Owner-scoped, and never deletes a shared default category. */
    @Query(
        """
        DELETE FROM categories
        WHERE id IN (:ids)
          AND isDefault = 0
          AND (ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId))
        """
    )
    suspend fun deleteByIds(ids: List<UUID>, ownerId: String)
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
        WHERE id = :id
          AND (ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId))
        """
    )
    suspend fun byId(id: UUID, ownerId: String): FriendEntity?

    @Query(
        """
        SELECT * FROM friends
        WHERE ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun allForOwner(ownerId: String): List<FriendEntity>

    @Query(
        """
        SELECT * FROM friends
        WHERE syncStateRaw != :syncedState
          AND (ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId))
        """
    )
    suspend fun pendingPush(ownerId: String, syncedState: Int = SyncState.SYNCED.raw): List<FriendEntity>

    @Upsert
    suspend fun upsert(friends: List<FriendEntity>)

    @Query(
        """
        DELETE FROM friends
        WHERE id IN (:ids)
          AND (ownerId IS NULL OR LOWER(ownerId) = LOWER(:ownerId))
        """
    )
    suspend fun deleteByIds(ids: List<UUID>, ownerId: String)
}

@Dao
interface UserProfileDao {

    /**
     * Keyed by `id`, which IS `auth.uid` — the profile row's primary key is the
     * account identifier, so no separate owner column exists to scope by.
     */
    @Query("SELECT * FROM user_profiles WHERE LOWER(id) = LOWER(:userId) LIMIT 1")
    suspend fun forUser(userId: String): UserProfileEntity?

    @Upsert
    suspend fun upsert(profile: UserProfileEntity)

    @Query("DELETE FROM user_profiles WHERE LOWER(id) = LOWER(:userId)")
    suspend fun deleteForUser(userId: String)
}
