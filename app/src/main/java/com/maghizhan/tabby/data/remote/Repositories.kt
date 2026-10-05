package com.maghizhan.tabby.data.remote

import com.maghizhan.tabby.data.remote.model.RemoteCategoryRow
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.remote.model.RemoteFriendRow
import java.util.UUID

/**
 * Repository contracts, mirroring the iOS protocols.
 *
 * Note what is NOT a parameter: the owner id. Every implementation derives
 * `user_id` from the *authenticated session* rather than trusting a caller, so a
 * bug or a hostile call site cannot read or write another account's rows even if
 * RLS were misconfigured. A caller-supplied owner id was a confused-deputy hole:
 * a stale `ownerId` cached locally could be replayed against the backend.
 *
 * Fetches return [CompleteSnapshot], which can only be produced by paging to
 * termination — absence-based deletion is unsound against a partial result, and
 * the previous `isComplete` boolean could simply be ignored.
 */
interface ExpenseRepositoring {
    suspend fun fetchAll(): CompleteSnapshot<RemoteExpenseRow>
    suspend fun upsert(rows: List<RemoteExpenseRow>)
    suspend fun delete(ids: List<UUID>)
}

interface CategoryRepositoring {
    suspend fun fetchAll(): CompleteSnapshot<RemoteCategoryRow>
    suspend fun upsert(rows: List<RemoteCategoryRow>)
    suspend fun delete(ids: List<UUID>)
}

interface FriendRepositoring {
    suspend fun fetchAll(): CompleteSnapshot<RemoteFriendRow>
    suspend fun upsert(rows: List<RemoteFriendRow>)
    suspend fun delete(ids: List<UUID>)
}

/** Raised when an operation needs an authenticated session and there is none. */
class NotAuthenticatedException : Exception("No authenticated Supabase session.")
