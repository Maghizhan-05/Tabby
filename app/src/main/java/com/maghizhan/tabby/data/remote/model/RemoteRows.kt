package com.maghizhan.tabby.data.remote.model

import com.maghizhan.tabby.data.sync.MoneyValidation
import com.maghizhan.tabby.data.sync.SyncState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * Tolerant only where PostgREST is genuinely ambiguous — `numeric` arrives as a
 * JSON number *or* a quoted string, and `timestamptz` with or without fractional
 * seconds. Money is decoded to [BigDecimal] and never routed through Double,
 * which is the exact-decimal guarantee the iOS side gets from `Decimal`.
 *
 * Everywhere else decoding is strict and throws, because silent coercion
 * changes user data:
 *
 * - JSON `null` is distinguished from the four-character string `"null"`, so a
 *   note a user actually typed as "null" survives instead of becoming absent.
 * - A field declared as text must BE JSON text; a number or boolean there is a
 *   schema mismatch worth failing on, not something to stringify.
 * - A malformed boolean or integer throws rather than falling back to a default,
 *   which would quietly mislabel a user category as a shared default.
 */
internal object RowDecoding {

    fun obj(element: JsonElement, context: String): JsonObject =
        element as? JsonObject ?: throw RowDecodingException("Not an object: $context")

    /**
     * The field's scalar value, or null when the key is absent or holds JSON
     * `null`. A non-scalar (object/array) is a schema mismatch and throws.
     */
    private fun primitiveOrNull(row: JsonObject, key: String): JsonPrimitive? {
        val value = row[key] ?: return null
        if (value is JsonNull) return null
        return value as? JsonPrimitive
            ?: throw RowDecodingException("Field is not a scalar: $key")
    }

    /** A required text field. Must be JSON text, not a coerced number/boolean. */
    fun string(row: JsonObject, key: String): String {
        val primitive = primitiveOrNull(row, key)
            ?: throw RowDecodingException("Missing field: $key")
        if (!primitive.isString) throw RowDecodingException("Field is not a string: $key")
        return primitive.content
    }

    /**
     * An optional text field. Only an absent key or JSON `null` yields null —
     * the literal string "null" is a legitimate value and is preserved.
     */
    fun stringOrNull(row: JsonObject, key: String): String? {
        val primitive = primitiveOrNull(row, key) ?: return null
        if (!primitive.isString) throw RowDecodingException("Field is not a string: $key")
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

    /** `numeric`: accepts a JSON number or a quoted string, never a Double. */
    fun decimal(row: JsonObject, key: String): BigDecimal {
        val primitive = primitiveOrNull(row, key)
            ?: throw RowDecodingException("Missing field: $key")
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

    /** Absent or null uses [default]; a present but malformed value throws. */
    fun boolean(row: JsonObject, key: String, default: Boolean): Boolean {
        val primitive = primitiveOrNull(row, key) ?: return default
        return primitive.content.toBooleanStrictOrNull()
            ?: throw RowDecodingException("Not a boolean: $key=${primitive.content}")
    }

    /** Absent or null uses [default]; a present but malformed value throws. */
    fun int(row: JsonObject, key: String, default: Int): Int {
        val primitive = primitiveOrNull(row, key) ?: return default
        return primitive.content.toIntOrNull()
            ?: throw RowDecodingException("Not an integer: $key=${primitive.content}")
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
            // Validated on the way in: an amount the backend column cannot hold
            // must fail here rather than be silently rounded when persisted.
            amount = MoneyValidation.normalizedAmount(RowDecoding.decimal(row, "amount")),
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
            // Balances are "how much is owed" and cannot be negative — the
            // direction lives in which of the two columns holds the value.
            theyOweUs = MoneyValidation.normalizedBalance(
                RowDecoding.decimal(row, "they_owe_us"), "they_owe_us"
            ),
            weOweThem = MoneyValidation.normalizedBalance(
                RowDecoding.decimal(row, "we_owe_them"), "we_owe_them"
            ),
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
