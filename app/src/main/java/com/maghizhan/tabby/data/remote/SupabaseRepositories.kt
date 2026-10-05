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
 * 2. **Completeness is proved by an exact server-side count, not by a short
 *    page.** See [fetchComplete].
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
     * Fetches every row for the authenticated user and proves the result is
     * complete by comparing it against an exact server-side count.
     *
     * Why not "page until a short page arrives": offset ranges are evaluated
     * against a *different* server statement on each request. If a row in an
     * earlier page is deleted between requests, every later row shifts down one
     * offset and exactly one row is never returned — and because the snapshot
     * then looks complete, reconciliation reads that skipped row as deleted
     * remotely and destroys it locally. Ordering by an immutable `id` does not
     * help: the problem is the shifting offset window, not the sort key.
     *
     * The fix is to make completeness checkable rather than assumed. PostgREST
     * returns an exact total alongside the rows (`Count.EXACT`), so:
     *
     * - rows gathered == the reported total -> provably complete, and absence
     *   deletion is authorised.
     * - anything else (rows missing, a count that moved mid-fetch, the page
     *   ceiling hit) -> NOT complete, and the snapshot is marked partial so
     *   reconciliation performs non-destructive merges only.
     *
     * A concurrent delete therefore degrades to "merge but do not delete this
     * cycle" and self-corrects next cycle, instead of destroying a row.
     */
    protected suspend fun <T> fetchComplete(decode: (JsonElement) -> List<T>): CompleteSnapshot<T> {
        val userId = requireUserId()

        val rows = mutableListOf<T>()
        var expectedTotal: Long? = null
        var pages = 0
        var countStable = true

        while (pages < CompleteSnapshot.MAXIMUM_PAGES) {
            val from = pages.toLong() * PAGE_SIZE
            val to = from + PAGE_SIZE - 1

            val response = client.from(table).select {
                filter { eq("user_id", userId) }
                order(column = "id", order = Order.ASCENDING)
                range(from, to)
                // Exact total for THIS filter, returned with the page.
                count(Count.EXACT)
            }

            val total = response.countOrNull()
            if (expectedTotal == null) {
                expectedTotal = total
            } else if (total != null && total != expectedTotal) {
                // The table changed underneath us; this fetch cannot be proved
                // complete, so it must not authorise deletions.
                countStable = false
            }

            val page = decode(Json.parseToJsonElement(response.data))
            rows += page
            pages++

            if (page.size < PAGE_SIZE) break
            if (expectedTotal != null && rows.size >= expectedTotal) break
        }

        val proved = countStable &&
            expectedTotal != null &&
            rows.size.toLong() == expectedTotal &&
            pages < CompleteSnapshot.MAXIMUM_PAGES

        return if (proved) {
            CompleteSnapshot.proved(rows = rows, ownerId = userId, pagesFetched = pages)
        } else {
            CompleteSnapshot.partial(rows = rows, ownerId = userId, pagesFetched = pages)
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

    companion object {
        /**
         * Rows per request. Below Supabase's default 1000-row cap, so a page is
         * never silently truncated by the server.
         */
        const val PAGE_SIZE = 500L
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
        // Balances validated at the upload boundary, as with expense amounts.
        put("they_owe_us", MoneyValidation.normalizedBalance(row.theyOweUs, "they_owe_us").toPlainString())
        put("we_owe_them", MoneyValidation.normalizedBalance(row.weOweThem, "we_owe_them").toPlainString())
        put("created_at", Timestamps.format(row.createdAt))
        put("updated_at", Timestamps.format(row.updatedAt))
    }
}
