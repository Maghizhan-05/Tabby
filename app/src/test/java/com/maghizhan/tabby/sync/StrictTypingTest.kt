package com.maghizhan.tabby.sync

import com.maghizhan.tabby.data.remote.model.RemoteCategoryRow
import com.maghizhan.tabby.data.remote.model.RowDecodingException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JSON type strictness.
 *
 * `JsonPrimitive.content` strips quotes, so reading it alone silently accepts a
 * STRING where the schema says boolean or integer. Swift's `Decodable` treats
 * that as a type mismatch, and the two clients must agree on what the backend is
 * allowed to send: a quoted boolean means something upstream is wrong, and
 * failing loudly is how we find out instead of diverging silently.
 */
class StrictTypingTest {

    private fun row(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    private val id = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"

    /**
     * `is_default` and `sort_order` are NOT NULL columns, so an absent or null
     * value means the backend sent something the schema forbids. Defaulting it
     * silently converts a shared seeded category into a user-owned one, which
     * then syncs and can be deleted for every account.
     */
    @Test
    fun `an absent required category field is rejected`() {
        val error = assertThrows(RowDecodingException::class.java) {
            RemoteCategoryRow.from(
                row("""{"id":"$id","user_id":"owner-a","name":"Food","sort_order":2}""")
            )
        }
        assertEquals(true, error.message!!.contains("is_default"))
    }

    @Test
    fun `a JSON-null required category field is rejected`() {
        val error = assertThrows(RowDecodingException::class.java) {
            RemoteCategoryRow.from(
                row("""{"id":"$id","user_id":"owner-a","name":"Food","is_default":null,"sort_order":2}""")
            )
        }
        assertEquals(true, error.message!!.contains("is_default"))
    }

    @Test
    fun `an absent required sort order is rejected`() {
        val error = assertThrows(RowDecodingException::class.java) {
            RemoteCategoryRow.from(
                row("""{"id":"$id","user_id":"owner-a","name":"Food","is_default":false}""")
            )
        }
        assertEquals(true, error.message!!.contains("sort_order"))
    }

    @Test
    fun `a genuine boolean is accepted`() {
        val decoded = RemoteCategoryRow.from(
            row("""{"id":"$id","user_id":"owner-a","name":"Food","is_default":true,"sort_order":2}""")
        )
        assertEquals(true, decoded.isDefault)
        assertEquals(2, decoded.sortOrder)
    }

    /** The finding: `"true"` is a string, not a boolean. */
    @Test
    fun `a quoted boolean is rejected`() {
        val error = assertThrows(RowDecodingException::class.java) {
            RemoteCategoryRow.from(
                row("""{"id":"$id","user_id":"owner-a","name":"Food","is_default":"true","sort_order":2}""")
            )
        }
        assertEquals(true, error.message!!.contains("quoted string"))
    }

    @Test
    fun `a quoted integer is rejected`() {
        val error = assertThrows(RowDecodingException::class.java) {
            RemoteCategoryRow.from(
                row("""{"id":"$id","user_id":"owner-a","name":"Food","is_default":false,"sort_order":"12"}""")
            )
        }
        assertEquals(true, error.message!!.contains("quoted string"))
    }

    @Test
    fun `a non-boolean literal is rejected`() {
        assertThrows(RowDecodingException::class.java) {
            RemoteCategoryRow.from(
                row("""{"id":"$id","user_id":"owner-a","name":"Food","is_default":2,"sort_order":1}""")
            )
        }
    }

    /**
     * `numeric` is the ONE exception: PostgREST returns it as a quoted string
     * precisely so clients do not route money through a binary float, so
     * rejecting strings there would reject the backend's own valid output.
     */
    @Test
    fun `a quoted numeric is still accepted for money`() {
        val decoded = com.maghizhan.tabby.data.remote.model.RemoteExpenseRow.from(
            row(
                """
                {"id":"$id","user_id":"owner-a","amount":"12.34","category_name":"Coffee",
                 "note":null,"date":"2026-01-01T00:00:00+00:00",
                 "created_at":"2026-01-01T00:00:00+00:00","updated_at":"2026-01-01T00:00:00+00:00"}
                """.trimIndent()
            )
        )
        assertEquals("12.34", decoded.amount.toPlainString())
    }


}
