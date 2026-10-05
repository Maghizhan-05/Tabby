package com.maghizhan.tabby.data.remote

import com.maghizhan.tabby.data.remote.model.RemoteCategoryRow
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.remote.model.RemoteFriendRow
import java.util.UUID

/**
 * A page of remote rows plus whether the backend had more to give.
 *
 * The reconciliation rules treat absence from a snapshot as "deleted on another
 * device", so a *partial* snapshot would silently delete real records. Callers
 * must therefore keep paging until [isComplete] is true before reconciling —
 * this type exists to make that requirement impossible to overlook, mirroring
 * the iOS `PaginatedSnapshot`.
 */
data class PaginatedSnapshot<T>(
    val rows: List<T>,
    val isComplete: Boolean
)

/**
 * Repository contracts, mirroring the iOS protocols. Every method is
 * owner-verified: the active owner id is passed explicitly rather than read from
 * ambient state, so a signed-out or mismatched caller cannot reach another
 * account's rows even if RLS were misconfigured.
 */
interface ExpenseRepositoring {
    suspend fun fetchAll(ownerId: String): PaginatedSnapshot<RemoteExpenseRow>
    suspend fun upsert(rows: List<RemoteExpenseRow>, ownerId: String)
    suspend fun delete(ids: List<UUID>, ownerId: String)
}

interface CategoryRepositoring {
    suspend fun fetchAll(ownerId: String): PaginatedSnapshot<RemoteCategoryRow>
    suspend fun upsert(rows: List<RemoteCategoryRow>, ownerId: String)
    suspend fun delete(ids: List<UUID>, ownerId: String)
}

interface FriendRepositoring {
    suspend fun fetchAll(ownerId: String): PaginatedSnapshot<RemoteFriendRow>
    suspend fun upsert(rows: List<RemoteFriendRow>, ownerId: String)
    suspend fun delete(ids: List<UUID>, ownerId: String)
}
