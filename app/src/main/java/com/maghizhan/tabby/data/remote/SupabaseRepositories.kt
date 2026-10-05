package com.maghizhan.tabby.data.remote

import com.maghizhan.tabby.data.remote.model.RemoteCategoryRow
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.remote.model.RemoteFriendRow
import com.maghizhan.tabby.data.remote.model.Timestamps
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Shared behaviour for the Supabase-backed repositories.
 *
 * Two rules every subclass inherits:
 *
 * 1. **`user_id` comes from the live session, never from a caller or a row.**
 *    [requireUserId] reads it from the Supabase auth state at the moment of the
 *    call. A caller-supplied owner id would be a confused-deputy hole: a stale
 *    `ownerId` cached locally could otherwise be used to read or write another
 *    account's rows, with RLS as the only thing standing in the way.
 *
 * 2. **Fetches page to completion.** [fetchComplete] drives
 *    [CompleteSnapshot.fetchPaging], which keeps requesting ordered ranges until
 *    a short page proves the end. Supabase caps an unpaged select at 1000 rows,
 *    so a single select is silently truncated for a heavy account — and a
 *    truncated snapshot would make reconciliation delete real records as
 *    "absent remotely".
 */
abstract class SupabaseRepository(private val table: String) {

    protected val client: SupabaseClient
        get() = SupabaseClientProvider.client ?: throw AuthError.NotConfigured

    protected fun requireUserId(): String =
        SupabaseClientProvider.currentUserId() ?: throw NotAuthenticatedException()

    /**
     * Pages the table for the authenticated user, ordered by `id` so the ranges
     * are stable between requests — ordering by a mutable column could skip or
     * duplicate rows as concurrent writes shift them between pages.
     */
    protected suspend fun <T> fetchComplete(decode: (JsonElement) -> List<T>): CompleteSnapshot<T> {
        val userId = requireUserId()
        return CompleteSnapshot.fetchPaging { from, to ->
            val response = client.from(table).select {
                filter { eq("user_id", userId) }
                order(column = "id", order = io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                range(from, to)
            }
            decode(response.data.let { kotlinx.serialization.json.Json.parseToJsonElement(it) })
        }
    }

    protected suspend fun upsertRows(rows: List<JsonObject>) {
        if (rows.isEmpty()) return
        client.from(table).upsert(rows)
    }

    /**
     * Deletes by id AND by the session's user_id. The extra predicate means a
     * wrong or hostile id list cannot remove another account's rows even if RLS
     * were misconfigured — defence in depth, not a replacement for RLS.
     */
    protected suspend fun deleteRows(ids: List<UUID>) {
        if (ids.isEmpty()) return
        val userId = requireUserId()
        client.from(table).delete {
            filter {
                eq("user_id", userId)
                isIn("id", ids.map(UUID::toString))
            }
        }
    }
}

class SupabaseExpenseRepository : SupabaseRepository("expenses"), ExpenseRepositoring {

    override suspend fun fetchAll(): CompleteSnapshot<RemoteExpenseRow> =
        fetchComplete { RemoteExpenseRow.list(it) }

    override suspend fun upsert(rows: List<RemoteExpenseRow>) {
        val userId = requireUserId()
        upsertRows(rows.map { row -> encode(row, userId) })
    }

    override suspend fun delete(ids: List<UUID>) = deleteRows(ids)

    /** `user_id` is overwritten with the session's id, whatever the row claims. */
    private fun encode(row: RemoteExpenseRow, userId: String): JsonObject = buildJsonObject {
        put("id", row.id.toString())
        put("user_id", userId)
        // Money as a decimal string so Postgres parses exact numeric input
        // rather than a float literal.
        put("amount", row.amount.toPlainString())
        put("category_name", row.categoryName)
        put("note", row.note)
        put("date", Timestamps.format(row.date))
        put("created_at", Timestamps.format(row.createdAt))
        put("updated_at", Timestamps.format(row.updatedAt))
    }
}

class SupabaseCategoryRepository : SupabaseRepository("categories"), CategoryRepositoring {

    override suspend fun fetchAll(): CompleteSnapshot<RemoteCategoryRow> =
        fetchComplete { RemoteCategoryRow.list(it) }

    override suspend fun upsert(rows: List<RemoteCategoryRow>) {
        val userId = requireUserId()
        upsertRows(rows.map { row -> encode(row, userId) })
    }

    override suspend fun delete(ids: List<UUID>) = deleteRows(ids)

    private fun encode(row: RemoteCategoryRow, userId: String): JsonObject = buildJsonObject {
        put("id", row.id.toString())
        put("user_id", userId)
        put("name", row.name)
        put("is_default", row.isDefault)
        put("sort_order", row.sortOrder)
    }
}

class SupabaseFriendRepository : SupabaseRepository("friends"), FriendRepositoring {

    override suspend fun fetchAll(): CompleteSnapshot<RemoteFriendRow> =
        fetchComplete { RemoteFriendRow.list(it) }

    override suspend fun upsert(rows: List<RemoteFriendRow>) {
        val userId = requireUserId()
        upsertRows(rows.map { row -> encode(row, userId) })
    }

    override suspend fun delete(ids: List<UUID>) = deleteRows(ids)

    private fun encode(row: RemoteFriendRow, userId: String): JsonObject = buildJsonObject {
        put("id", row.id.toString())
        put("user_id", userId)
        put("name", row.name)
        put("they_owe_us", row.theyOweUs.toPlainString())
        put("we_owe_them", row.weOweThem.toPlainString())
        put("created_at", Timestamps.format(row.createdAt))
        put("updated_at", Timestamps.format(row.updatedAt))
    }
}
