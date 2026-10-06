package com.maghizhan.tabby.data.local

import androidx.room.Dao
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
 * Three invariants hold for every account-sensitive statement:
 *
 * 1. **Exact-owner scoping.** Every predicate matches `LOWER(ownerId) =
 *    LOWER(:ownerId)` and nothing else. Earlier versions also matched
 *    `ownerId IS NULL` so pre-sign-in rows could be adopted, but that made an
 *    unclaimed row readable and processable by *every* account in turn: sign out,
 *    sign in as someone else, and the first user's unsynced expense was visible
 *    and would be uploaded under the second account. Legacy rows are now adopted
 *    by [ExpenseDao.claimLegacyRows] at the start of a cycle — an explicit,
 *    atomic, one-time transfer — after which only exact-owner predicates apply.
 *
 * 2. **Tombstones are invisible.** A DELETED row is pending remote deletion and
 *    must not appear in any list, for any entity type.
 *
 * 3. **Acknowledgement is compare-and-set.** See [ExpenseDao.markSyncedIfUnchanged].
 *
 * Deletes are id-scoped AND owner-scoped: an id alone is attacker- or
 * bug-controlled, and `DELETE ... WHERE id IN (:ids)` would erase another
 * account's row.
 *
 * These DAOs are internal to the data layer. Application code writes through
 * `ExpenseStore`/`CategoryStore`/`FriendStore`, which own the authorise-then-
 * persist transaction; a raw `@Upsert` cannot check an existing row's owner
 * because INSERT has no WHERE clause.
 */
@Dao
interface ExpenseDao {

    @Query(
        """
        SELECT * FROM expenses
        WHERE syncStateRaw != :deletedState
          AND LOWER(ownerId) = LOWER(:ownerId)
        ORDER BY date DESC
        """
    )
    fun observeVisible(ownerId: String, deletedState: Int = SyncState.DELETED.raw): Flow<List<ExpenseEntity>>

    /** Owner-scoped: a bare id lookup could surface another account's expense. */
    @Query(
        """
        SELECT * FROM expenses
        WHERE id = :id
          AND LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun byId(id: UUID, ownerId: String): ExpenseEntity?

    /**
     * Reads a row by id with NO owner predicate.
     *
     * Used only by the store's authorise-then-persist transaction, to discover
     * whether an incoming row's UUID already belongs to a different account. An
     * owner-scoped read cannot answer that question: it returns null both when
     * the row is absent and when it belongs to someone else, and treating those
     * the same lets an upsert overwrite a foreign row.
     */
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun byIdUnscoped(id: UUID): ExpenseEntity?

    @Query(
        """
        SELECT * FROM expenses
        WHERE LOWER(ownerId) = LOWER(:ownerId)
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
          AND LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun pendingPush(ownerId: String, syncedState: Int = SyncState.SYNCED.raw): List<ExpenseEntity>

    /**
     * Adopts rows written before any account existed, assigning them to
     * [ownerId]. Returns the number claimed.
     *
     * One atomic statement, and only `ownerId IS NULL` is touched, so it can
     * never move a row between two real accounts. Run once per cycle before
     * anything else reads: afterwards every predicate is exact-owner, and an
     * unclaimed row is invisible to everyone rather than visible to everyone.
     */
    @Query("UPDATE expenses SET ownerId = :ownerId WHERE ownerId IS NULL")
    suspend fun claimLegacyRows(ownerId: String): Int

    @Query(
        """
        SELECT id FROM expenses
        WHERE id IN (:ids)
          AND ownerId IS NOT NULL
          AND LOWER(ownerId) != LOWER(:ownerId)
        """
    )
    suspend fun foreignOwnedIds(ids: List<UUID>, ownerId: String): List<UUID>

    /** Internal: the store owns the authorise-then-persist transaction. */
    @Upsert
    suspend fun upsert(expenses: List<ExpenseEntity>)

    /**
     * Compare-and-set acknowledgement. Returns the number of rows updated.
     *
     * Marking a row SYNCED by id and owner alone loses concurrent work: the
     * coordinator reads pending rows, performs network I/O, then acknowledges —
     * and an edit the user made *during* that upload is silently cleared, so the
     * change is never uploaded and looks saved. Requiring the revision and state
     * to be exactly what was uploaded means a row touched meanwhile simply does
     * not match, stays pending, and goes up next cycle.
     *
     * The revision is bumped so a second acknowledgement of the same upload
     * cannot match again.
     */
    @Query(
        """
        UPDATE expenses
        SET syncStateRaw = :syncedState,
            remoteId = CAST(id AS TEXT),
            revision = revision + 1
        WHERE id = :id
          AND LOWER(ownerId) = LOWER(:ownerId)
          AND revision = :expectedRevision
          AND syncStateRaw = :expectedState
        """
    )
    suspend fun markSyncedIfUnchanged(
        id: UUID,
        ownerId: String,
        expectedRevision: Int,
        expectedState: Int,
        syncedState: Int = SyncState.SYNCED.raw
    ): Int

    /**
     * Compare-and-set tombstone removal. Returns the number of rows deleted.
     *
     * A tombstone is only physically removed if it is still the exact tombstone
     * that was deleted remotely. Without the revision and state check, a row
     * resurrected or re-edited during the remote delete would be destroyed along
     * with it.
     */
    @Query(
        """
        DELETE FROM expenses
        WHERE id = :id
          AND LOWER(ownerId) = LOWER(:ownerId)
          AND revision = :expectedRevision
          AND syncStateRaw = :expectedState
        """
    )
    suspend fun deleteTombstoneIfUnchanged(
        id: UUID,
        ownerId: String,
        expectedRevision: Int,
        expectedState: Int = SyncState.DELETED.raw
    ): Int

    @Query(
        """
        DELETE FROM expenses
        WHERE id IN (:ids)
          AND LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun deleteByIds(ids: List<UUID>, ownerId: String)
}

@Dao
interface CategoryDao {

    /**
     * Default categories are shared (no owner) and always visible; user
     * categories are exact-owner scoped. DELETED tombstones are excluded just as
     * for expenses and friends — a category the user deleted must not reappear
     * in the picker while its deletion is still pending.
     */
    @Query(
        """
        SELECT * FROM categories
        WHERE syncStateRaw != :deletedState
          AND (isDefault = 1 OR LOWER(ownerId) = LOWER(:ownerId))
        ORDER BY sortOrder ASC, name ASC
        """
    )
    fun observeForOwner(ownerId: String, deletedState: Int = SyncState.DELETED.raw): Flow<List<CategoryEntity>>

    @Query(
        """
        SELECT * FROM categories
        WHERE isDefault = 1 OR LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun allForOwner(ownerId: String): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun byIdUnscoped(id: UUID): CategoryEntity?

    /**
     * Total row count, including tombstones and defaults.
     *
     * Used only by first-run seeding, which must distinguish "no categories have
     * ever existed" from "the user deleted the defaults". A visibility-filtered
     * count would read zero in the second case and re-seed rows the user
     * deliberately removed.
     */
    @Query("SELECT COUNT(*) FROM categories")
    suspend fun countAll(): Int

    /**
     * Every category row, including the shared seeded defaults.
     *
     * Unscoped because the defaults carry no owner, so an owner-scoped read
     * cannot see them; used by seeding and by the picker, which must offer the
     * defaults alongside the account's own categories.
     */
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    suspend fun allUnscoped(): List<CategoryEntity>

    /**
     * Hard-deletes a seeded default.
     *
     * Defaults are the one category kind that may be erased rather than
     * tombstoned: they have no remote counterpart (they are never pushed), so
     * there is nothing for a tombstone to inform and nothing to resurrect them.
     * Guarded by `isDefault = 1` so this can never erase user data.
     */
    @Query("DELETE FROM categories WHERE id = :id AND isDefault = 1")
    suspend fun deleteDefaultById(id: UUID)

    @Query(
        """
        SELECT * FROM categories
        WHERE syncStateRaw != :syncedState
          AND isDefault = 0
          AND LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun pendingPush(ownerId: String, syncedState: Int = SyncState.SYNCED.raw): List<CategoryEntity>

    /** Claims only non-default legacy rows; shared defaults keep `ownerId IS NULL`. */
    @Query("UPDATE categories SET ownerId = :ownerId WHERE ownerId IS NULL AND isDefault = 0")
    suspend fun claimLegacyRows(ownerId: String): Int

    /**
     * Ids in [ids] that this account may NOT write.
     *
     * A shared default counts as foreign too: it belongs to no account and the
     * store already refuses to let a signed-in user claim one, so a pulled row
     * that happens to carry a default's UUID must not replace it either.
     */
    @Query(
        """
        SELECT id FROM categories
        WHERE id IN (:ids)
          AND (isDefault = 1 OR (ownerId IS NOT NULL AND LOWER(ownerId) != LOWER(:ownerId)))
        """
    )
    suspend fun foreignOwnedIds(ids: List<UUID>, ownerId: String): List<UUID>

    /** Internal: the store owns the authorise-then-persist transaction. */
    @Upsert
    suspend fun upsert(categories: List<CategoryEntity>)

    /** See [ExpenseDao.markSyncedIfUnchanged]. */
    @Query(
        """
        UPDATE categories
        SET syncStateRaw = :syncedState,
            remoteId = CAST(id AS TEXT),
            revision = revision + 1
        WHERE id = :id
          AND isDefault = 0
          AND LOWER(ownerId) = LOWER(:ownerId)
          AND revision = :expectedRevision
          AND syncStateRaw = :expectedState
        """
    )
    suspend fun markSyncedIfUnchanged(
        id: UUID,
        ownerId: String,
        expectedRevision: Int,
        expectedState: Int,
        syncedState: Int = SyncState.SYNCED.raw
    ): Int

    /** See [ExpenseDao.deleteTombstoneIfUnchanged]. */
    @Query(
        """
        DELETE FROM categories
        WHERE id = :id
          AND isDefault = 0
          AND LOWER(ownerId) = LOWER(:ownerId)
          AND revision = :expectedRevision
          AND syncStateRaw = :expectedState
        """
    )
    suspend fun deleteTombstoneIfUnchanged(
        id: UUID,
        ownerId: String,
        expectedRevision: Int,
        expectedState: Int = SyncState.DELETED.raw
    ): Int

    /** Owner-scoped, and never deletes a shared default category. */
    @Query(
        """
        DELETE FROM categories
        WHERE id IN (:ids)
          AND isDefault = 0
          AND LOWER(ownerId) = LOWER(:ownerId)
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
          AND LOWER(ownerId) = LOWER(:ownerId)
        ORDER BY name ASC
        """
    )
    fun observeVisible(ownerId: String, deletedState: Int = SyncState.DELETED.raw): Flow<List<FriendEntity>>

    @Query(
        """
        SELECT * FROM friends
        WHERE id = :id
          AND LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun byId(id: UUID, ownerId: String): FriendEntity?

    @Query("SELECT * FROM friends WHERE id = :id")
    suspend fun byIdUnscoped(id: UUID): FriendEntity?

    @Query(
        """
        SELECT * FROM friends
        WHERE LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun allForOwner(ownerId: String): List<FriendEntity>

    @Query(
        """
        SELECT * FROM friends
        WHERE syncStateRaw != :syncedState
          AND LOWER(ownerId) = LOWER(:ownerId)
        """
    )
    suspend fun pendingPush(ownerId: String, syncedState: Int = SyncState.SYNCED.raw): List<FriendEntity>

    @Query("UPDATE friends SET ownerId = :ownerId WHERE ownerId IS NULL")
    suspend fun claimLegacyRows(ownerId: String): Int

    @Query(
        """
        SELECT id FROM friends
        WHERE id IN (:ids)
          AND ownerId IS NOT NULL
          AND LOWER(ownerId) != LOWER(:ownerId)
        """
    )
    suspend fun foreignOwnedIds(ids: List<UUID>, ownerId: String): List<UUID>

    /** Internal: the store owns the authorise-then-persist transaction. */
    @Upsert
    suspend fun upsert(friends: List<FriendEntity>)

    /** See [ExpenseDao.markSyncedIfUnchanged]. */
    @Query(
        """
        UPDATE friends
        SET syncStateRaw = :syncedState,
            remoteId = CAST(id AS TEXT),
            revision = revision + 1
        WHERE id = :id
          AND LOWER(ownerId) = LOWER(:ownerId)
          AND revision = :expectedRevision
          AND syncStateRaw = :expectedState
        """
    )
    suspend fun markSyncedIfUnchanged(
        id: UUID,
        ownerId: String,
        expectedRevision: Int,
        expectedState: Int,
        syncedState: Int = SyncState.SYNCED.raw
    ): Int

    /** See [ExpenseDao.deleteTombstoneIfUnchanged]. */
    @Query(
        """
        DELETE FROM friends
        WHERE id = :id
          AND LOWER(ownerId) = LOWER(:ownerId)
          AND revision = :expectedRevision
          AND syncStateRaw = :expectedState
        """
    )
    suspend fun deleteTombstoneIfUnchanged(
        id: UUID,
        ownerId: String,
        expectedRevision: Int,
        expectedState: Int = SyncState.DELETED.raw
    ): Int

    @Query(
        """
        DELETE FROM friends
        WHERE id IN (:ids)
          AND LOWER(ownerId) = LOWER(:ownerId)
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
