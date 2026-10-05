package com.maghizhan.tabby.data.remote.model

import com.maghizhan.tabby.data.sync.SyncState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Raised when a backend row cannot be decoded; names the offending field. */
class RowDecodingException(message: String) : Exception(message)

/**
 * Decoding helpers shared by every remote row.
 *
 * Deliberately tolerant in the same places the iOS decoder is: PostgREST returns
 * `numeric` as a JSON number *or* a quoted string depending on configuration,
 * and `timestamptz` with or without fractional seconds. Money is decoded to
 * [BigDecimal] and never routed through Double — the exact-decimal guarantee the
 * iOS side gets from `Decimal`.
 */
internal object RowDecoding {

    fun obj(element: JsonElement, context: String): JsonObject =
        element as? JsonObject ?: throw RowDecodingException("Not an object: $context")

    fun string(row: JsonObject, key: String): String {
        val primitive = row[key] as? JsonPrimitive
            ?: throw RowDecodingException("Missing field: $key")
        if (primitive.isString) return primitive.content
        return primitive.content
    }

    fun stringOrNull(row: JsonObject, key: String): String? {
        val primitive = row[key] as? JsonPrimitive ?: return null
        if (primitive.content == "null") return null
        return primitive.content
    }

    fun uuid(row: JsonObject, key: String): UUID {
        val raw = string(row, key)
        return try {
            UUID.fromString(raw)
        } catch (_: IllegalArgumentException) {
            throw RowDecodingException("Not a uuid: $raw")
        }
    }

    fun decimal(row: JsonObject, key: String): BigDecimal {
        val primitive = row[key] as? JsonPrimitive
            ?: throw RowDecodingException("Not a decimal: $key")
        return try {
            BigDecimal(primitive.content)
        } catch (_: NumberFormatException) {
            throw RowDecodingException("Not a decimal: ${primitive.content}")
        }
    }

    fun instant(row: JsonObject, key: String): Instant {
        val raw = string(row, key)
        return Timestamps.parse(raw) ?: throw RowDecodingException("Not a timestamp: $raw")
    }

    fun boolean(row: JsonObject, key: String, default: Boolean): Boolean {
        val primitive = row[key] as? JsonPrimitive ?: return default
        return primitive.content.toBooleanStrictOrNull() ?: default
    }

    fun int(row: JsonObject, key: String, default: Int): Int {
        val primitive = row[key] as? JsonPrimitive ?: return default
        return primitive.content.toIntOrNull() ?: default
    }

    fun <T> array(element: JsonElement, decode: (JsonObject) -> T): List<T> {
        val array = element as? JsonArray ?: throw RowDecodingException("Not an array")
        return array.map { decode(obj(it, "row")) }
    }
}

/** A row as it exists in the backend `expenses` table. */
data class RemoteExpenseRow(
    val id: UUID,
    val userId: String,
    val amount: BigDecimal,
    val categoryName: String,
    val note: String?,
    val date: Instant,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun from(row: JsonObject): RemoteExpenseRow = RemoteExpenseRow(
            id = RowDecoding.uuid(row, "id"),
            userId = RowDecoding.string(row, "user_id"),
            amount = RowDecoding.decimal(row, "amount"),
            categoryName = RowDecoding.string(row, "category_name"),
            note = RowDecoding.stringOrNull(row, "note"),
            date = RowDecoding.instant(row, "date"),
            createdAt = RowDecoding.instant(row, "created_at"),
            updatedAt = RowDecoding.instant(row, "updated_at")
        )

        fun list(element: JsonElement): List<RemoteExpenseRow> =
            RowDecoding.array(element) { from(it) }
    }
}

/** A row as it exists in the backend `categories` table. */
data class RemoteCategoryRow(
    val id: UUID,
    val userId: String,
    val name: String,
    val isDefault: Boolean,
    val sortOrder: Int
) {
    companion object {
        fun from(row: JsonObject): RemoteCategoryRow = RemoteCategoryRow(
            id = RowDecoding.uuid(row, "id"),
            userId = RowDecoding.string(row, "user_id"),
            name = RowDecoding.string(row, "name"),
            isDefault = RowDecoding.boolean(row, "is_default", default = false),
            sortOrder = RowDecoding.int(row, "sort_order", default = 0)
        )

        fun list(element: JsonElement): List<RemoteCategoryRow> =
            RowDecoding.array(element) { from(it) }
    }
}

/** A row as it exists in the backend `friends` table. */
data class RemoteFriendRow(
    val id: UUID,
    val userId: String,
    val name: String,
    val theyOweUs: BigDecimal,
    val weOweThem: BigDecimal,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun from(row: JsonObject): RemoteFriendRow = RemoteFriendRow(
            id = RowDecoding.uuid(row, "id"),
            userId = RowDecoding.string(row, "user_id"),
            name = RowDecoding.string(row, "name"),
            theyOweUs = RowDecoding.decimal(row, "they_owe_us"),
            weOweThem = RowDecoding.decimal(row, "we_owe_them"),
            createdAt = RowDecoding.instant(row, "created_at"),
            updatedAt = RowDecoding.instant(row, "updated_at")
        )

        fun list(element: JsonElement): List<RemoteFriendRow> =
            RowDecoding.array(element) { from(it) }
    }
}

/** Local projection used by the reconciliation rules; see each Reconciliation type. */
data class LocalRecord(
    val id: UUID,
    val ownerId: String?,
    val updatedAt: Instant,
    val syncState: SyncState,
    /**
     * True when this record was previously uploaded (it carries a remote id).
     * Only such a record may be deleted by absence: for a row that never reached
     * the backend, absence tells us nothing.
     */
    val hasRemoteIdentity: Boolean = false
)
