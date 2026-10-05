package com.maghizhan.tabby.data.remote

import com.maghizhan.tabby.data.remote.model.RemoteCategoryRow
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.remote.model.RemoteFriendRow
import com.maghizhan.tabby.data.remote.model.Timestamps
import com.maghizhan.tabby.data.sync.MoneyValidation
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Shared behaviour for the Supabase-backed repositories.
 *
 * Three rules every subclass inherits:
 *
 * 1. **`user_id` comes from the live session, never from a caller or a row.**
 *    [requireUserId] reads it from the Supabase auth state at the moment of the
 *    call. A caller-supplied owner id would be a confused-deputy hole: a stale
 *    `ownerId` cached locally could otherwise be used to read or write another
 *    account's rows, with RLS as the only thing standing in the way.
 *
 * 2. **Completeness is decided by [CompleteSnapshot.fetch], not here.** This
 *    class only knows how to request one page and report the exact count the
 *    same statement returned; whether that constitutes proof is the snapshot's
 *    rule, so a repository cannot declare its own result complete.
 *
 * 3. **Money is validated again at the upload boundary**, because the backend
 *    column is the constraint that actually matters and a row may have been
 *    written locally before a rule existed.
 */
abstract class SupabaseRepository(private val table: String) {

    protected val client: SupabaseClient
        get() = SupabaseClientProvider.client ?: throw AuthError.NotConfigured

    protected fun requireUserId(): String =
        SupabaseClientProvider.currentUserId() ?: throw NotAuthenticatedException()

    /**
     * Requests rows for the authenticated user and hands them to
     * [CompleteSnapshot.fetch], which decides whether the result is provably
     * complete.
     *
     * Ordered by the immutable `id` so a page is at least internally
     * deterministic. Ordering does NOT make multi-request paging complete,
     * though: equal-cardinality churn between requests (one early row deleted,
     * one later row inserted) skips a row while the total stays put, and
     * duplicate ids across overlapping pages pad the count back up. Both forge
     * a false proof, which is why the snapshot refuses to call any multi-request
     * result proven and rejects duplicates outright.
     */
    protected suspend fun <T> fetchSnapshot(
        binding: SessionBinding,
        idOf: (T) -> Any,
        decode: (JsonElement) -> List<T>
    ): CompleteSnapshot<T> {
        val userId = requireUserId()
        return CompleteSnapshot.fetch(binding, idOf) { from, to ->
            val response = client.from(table).select {
                filter { eq("user_id", userId) }
                order(column = "id", order = Order.ASCENDING)
                range(from, to)
                // Exact total for THIS filter, returned with the page.
                count(Count.EXACT)
            }
            CompleteSnapshot.Page(
                rows = decode(Json.parseToJsonElement(response.data)),
                exactTotal = response.countOrNull()
            )
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

    override suspend fun fetchAll(binding: SessionBinding): CompleteSnapshot<RemoteExpenseRow> =
        fetchSnapshot(binding, { it.id }) { RemoteExpenseRow.list(it) }

    override suspend fun upsert(rows: List<RemoteExpenseRow>) {
        val userId = requireUserId()
        upsertRows(rows.map { row -> encode(row, userId) })
    }

    override suspend fun delete(ids: List<UUID>) = deleteRows(ids)

    /** `user_id` is overwritten with the session's id, whatever the row claims. */
    private fun encode(row: RemoteExpenseRow, userId: String): JsonObject = buildJsonObject {
        put("id", row.id.toString())
        put("user_id", userId)
        // Validated at the upload boundary, not merely on the way in: this is
        // the last point before the value reaches a numeric(12,2) column, where
        // an over-scale or out-of-range amount would fail opaquely mid-sync or
        // be silently rounded. Emitted as a decimal string so Postgres parses
        // exact numeric input rather than a float literal.
        put("amount", MoneyValidation.normalizedAmount(row.amount, "amount").toPlainString())
        put("category_name", row.categoryName)
        put("note", row.note)
        put("date", Timestamps.format(row.date))
        put("created_at", Timestamps.format(row.createdAt))
        put("updated_at", Timestamps.format(row.updatedAt))
    }
}

class SupabaseCategoryRepository : SupabaseRepository("categories"), CategoryRepositoring {

    override suspend fun fetchAll(binding: SessionBinding): CompleteSnapshot<RemoteCategoryRow> =
        fetchSnapshot(binding, { it.id }) { RemoteCategoryRow.list(it) }

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

    override suspend fun fetchAll(binding: SessionBinding): CompleteSnapshot<RemoteFriendRow> =
        fetchSnapshot(binding, { it.id }) { RemoteFriendRow.list(it) }

    override suspend fun upsert(rows: List<RemoteFriendRow>) {
        val userId = requireUserId()
        upsertRows(rows.map { row -> encode(row, userId) })
    }

    override suspend fun delete(ids: List<UUID>) = deleteRows(ids)

    private fun encode(row: RemoteFriendRow, userId: String): JsonObject = buildJsonObject {
        put("id", row.id.toString())
        put("user_id", userId)
        put("name", row.name)
        // Balances validated at the upload boundary, as with expense amounts.
        put("they_owe_us", MoneyValidation.normalizedBalance(row.theyOweUs, "they_owe_us").toPlainString())
        put("we_owe_them", MoneyValidation.normalizedBalance(row.weOweThem, "we_owe_them").toPlainString())
        put("created_at", Timestamps.format(row.createdAt))
        put("updated_at", Timestamps.format(row.updatedAt))
    }
}
