package com.maghizhan.tabby.sync

import com.maghizhan.tabby.data.remote.model.RemoteCategoryRow
import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.remote.model.RowDecodingException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Decoding must be strict where strictness protects data.
 *
 * Tolerance is limited to the places PostgREST is genuinely ambiguous (numeric
 * as number-or-string, timestamps with or without fractional seconds). Anywhere
 * else, coercion silently changes user data - so these tests pin the failures.
 */
class StrictDecodingTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    /**
     * Builds an expense row. [noteJson] is raw JSON so a test can supply the
     * literal `null`, a quoted string, or omit the field entirely.
     */
    private fun expenseJson(noteJson: String? = null, amount: String = "12.34"): JsonObject {
        val fields = mutableListOf(
            """"id": "11111111-1111-1111-1111-111111111111"""",
            """"user_id": "owner-a"""",
            """"amount": "$amount"""",
            """"category_name": "Food"""",
            """"date": "2026-01-02T03:04:05.123456Z"""",
            """"created_at": "2026-01-02T03:04:05Z"""",
            """"updated_at": "2026-01-02T03:04:05Z""""
        )
        if (noteJson != null) fields += """"note": $noteJson"""
        return obj(fields.joinToString(prefix = "{", postfix = "}"))
    }

    @Test
    fun `a note the user actually typed as the word null is preserved`() {
        // The bug this pins: treating the four-character string "null" as absent
        // silently erases a legitimate note.
        assertEquals("null", RemoteExpenseRow.from(expenseJson(noteJson = "\"null\"")).note)
    }

    @Test
    fun `a JSON null note decodes as absent`() {
        assertNull(RemoteExpenseRow.from(expenseJson(noteJson = "null")).note)
    }

    @Test
    fun `an omitted note decodes as absent`() {
        assertNull(RemoteExpenseRow.from(expenseJson()).note)
    }

    @Test
    fun `a normal note is unaffected`() {
        assertEquals("lunch", RemoteExpenseRow.from(expenseJson(noteJson = "\"lunch\"")).note)
    }

    @Test
    fun `a numeric value in a text field is a schema mismatch and throws`() {
        try {
            RemoteExpenseRow.from(
                obj(
                    """
                    {"id":"11111111-1111-1111-1111-111111111111","user_id":"owner-a",
                     "amount":"1.00","category_name":404,
                     "date":"2026-01-02T03:04:05Z","created_at":"2026-01-02T03:04:05Z",
                     "updated_at":"2026-01-02T03:04:05Z"}
                    """.trimIndent()
                )
            )
            fail("a number must not be coerced into a text field")
        } catch (e: RowDecodingException) {
            assertTrue(e.message!!.contains("category_name"))
        }
    }

    @Test
    fun `a malformed boolean throws instead of falling back to the default`() {
        // Falling back would quietly mislabel a user category as a shared default.
        try {
            RemoteCategoryRow.from(
                obj(
                    """
                    {"id":"22222222-2222-2222-2222-222222222222","user_id":"owner-a",
                     "name":"Food","is_default":"yes"}
                    """.trimIndent()
                )
            )
            fail("expected RowDecodingException")
        } catch (e: RowDecodingException) {
            assertTrue(e.message!!.contains("is_default"))
        }
    }

    @Test
    fun `a malformed integer throws instead of silently becoming zero`() {
        try {
            RemoteCategoryRow.from(
                obj(
                    """
                    {"id":"22222222-2222-2222-2222-222222222222","user_id":"owner-a",
                     "name":"Food","sort_order":"first"}
                    """.trimIndent()
                )
            )
            fail("expected RowDecodingException")
        } catch (e: RowDecodingException) {
            assertTrue(e.message!!.contains("sort_order"))
        }
    }

    @Test
    fun `an absent boolean still uses its documented default`() {
        val row = RemoteCategoryRow.from(
            obj("""{"id":"22222222-2222-2222-2222-222222222222","user_id":"owner-a","name":"Food"}""")
        )
        assertEquals(false, row.isDefault)
        assertEquals(0, row.sortOrder)
    }

    @Test
    fun `a missing required field names the field`() {
        try {
            RemoteExpenseRow.from(obj("""{"id":"11111111-1111-1111-1111-111111111111"}"""))
            fail("expected RowDecodingException")
        } catch (e: RowDecodingException) {
            assertTrue(e.message!!.contains("user_id"))
        }
    }

    @Test
    fun `a malformed uuid throws`() {
        try {
            RemoteExpenseRow.from(
                obj(
                    """
                    {"id":"not-a-uuid","user_id":"owner-a","amount":"1.00",
                     "category_name":"Food","date":"2026-01-02T03:04:05Z",
                     "created_at":"2026-01-02T03:04:05Z","updated_at":"2026-01-02T03:04:05Z"}
                    """.trimIndent()
                )
            )
            fail("expected RowDecodingException")
        } catch (e: RowDecodingException) {
            assertTrue(e.message!!.contains("uuid"))
        }
    }

    @Test
    fun `an out of range amount is rejected at decode time`() {
        // Validation on the way in, so a bad backend row cannot reach Room.
        try {
            RemoteExpenseRow.from(expenseJson(amount = "10000000000.00"))
            fail("expected an invalid-amount failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exceeds"))
        }
    }
}
