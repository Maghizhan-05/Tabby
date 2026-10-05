package com.maghizhan.tabby.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.maghizhan.tabby.data.sync.SyncState
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Room entities mapping 1:1 to the iOS SwiftData `@Model` classes. Field names,
 * nullability and the `syncStateRaw`/`ownerId`/`revision` sync contract are
 * preserved so both apps agree on what a record means.
 */

@Entity(
    tableName = "expenses",
    indices = [Index("ownerId"), Index("date"), Index("syncStateRaw")]
)
data class ExpenseEntity(
    @PrimaryKey val id: UUID,
    val amount: BigDecimal,
    val categoryName: String,
    val note: String?,
    val date: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val syncStateRaw: Int,
    val remoteId: String?,
    /**
     * Supabase user id that owns this record. Null only for legacy rows created
     * before ownership partitioning; those are claimed by the next signed-in
     * user on the first push and are never re-stamped afterwards.
     */
    val ownerId: String?,
    /**
     * Monotonic local revision, bumped on every local edit. A push may only mark
     * a record SYNCED when the revision it uploaded is still current, so an edit
     * made while an upload is in flight is never lost.
     */
    val revision: Int = 0
) {
    val syncState: SyncState get() = SyncState.fromRaw(syncStateRaw)

    companion object {
        const val MAXIMUM_NOTE_LENGTH = 120

        /** Mirrors the iOS `Expense.normalizedNote`: blank notes become null. */
        fun normalizedNote(note: String?): String? = note?.trim()?.ifEmpty { null }
    }
}

@Entity(tableName = "categories", indices = [Index("ownerId")])
data class CategoryEntity(
    @PrimaryKey val id: UUID,
    val name: String,
    val isDefault: Boolean,
    val sortOrder: Int,
    /**
     * Defaults to SYNCED for seeded categories so they are not pushed until
     * actually changed, while newly created custom categories start LOCAL.
     */
    val syncStateRaw: Int,
    val remoteId: String?,
    /** Null for the seeded defaults and for legacy pre-partitioning rows. */
    val ownerId: String?,
    /** See [ExpenseEntity.revision]: required for compare-and-set acknowledgement. */
    val revision: Int = 0
) {
    val syncState: SyncState get() = SyncState.fromRaw(syncStateRaw)
}

@Entity(tableName = "friends", indices = [Index("ownerId")])
data class FriendEntity(
    @PrimaryKey val id: UUID,
    val name: String,
    val ownerId: String?,
    val theyOweUs: BigDecimal,
    val weOweThem: BigDecimal,
    val createdAt: Instant,
    val updatedAt: Instant,
    val syncStateRaw: Int,
    val remoteId: String? = null,
    /** See [ExpenseEntity.revision]: required for compare-and-set acknowledgement. */
    val revision: Int = 0
) {
    val syncState: SyncState get() = SyncState.fromRaw(syncStateRaw)

    /** Positive means this friend owes us; negative means we owe them. */
    val netBalance: BigDecimal get() = theyOweUs.subtract(weOweThem)
}

/**
 * The account's profile row.
 *
 * Mirrors the iOS `UserProfile` and the backend `profiles` table exactly: `id`
 * IS `auth.uid` (so there is no separate owner column to scope by), plus `email`
 * and an optional `displayName`. Android-only preferences such as a currency
 * override deliberately do NOT live here — they are not in the shared schema,
 * and adding them would make this table diverge from the row the iOS app and the
 * backend agree on.
 */
@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    /** Equals the authenticated user's id (`auth.uid`), stored as text. */
    @PrimaryKey val id: String,
    val email: String,
    val displayName: String?,
    /** Set by the backend; not a local clock. */
    val createdAt: Instant
)
