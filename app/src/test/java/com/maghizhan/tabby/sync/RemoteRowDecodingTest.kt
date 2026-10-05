package com.maghizhan.tabby.sync

import com.maghizhan.tabby.data.remote.model.RemoteExpenseRow
import com.maghizhan.tabby.data.remote.model.Timestamps
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Ported from the iOS `testRemoteRowDecodesPostgRESTShapes`. PostgREST returns
 * `numeric` as a JSON number or a quoted string, and `timestamptz` with or
 * without fractional seconds, so both shapes must decode — and money must keep
 * its exact decimal value rather than going through a binary float.
 */
class RemoteRowDecodingTest {

    @Test
    fun `remote rows decode both postgrest numeric and timestamp shapes`() {
        val id = UUID.randomUUID()
        val json = """
        [
          {
            "id": "$id",
            "user_id": "owner-a",
            "amount": "12.34",
            "category_name": "Food",
            "note": "Lunch",
            "date": "2026-10-01T10:00:00+00:00",
            "created_at": "2026-10-01T10:00:00.123456+00:00",
            "updated_at": "2026-10-01T10:00:00Z"
          },
          {
            "id": "${UUID.randomUUID()}",
            "user_id": "owner-a",
            "amount": 5,
            "category_name": "Transport",
            "note": null,
            "date": "2026-10-01T10:00:00+00:00",
            "created_at": "2026-10-01T10:00:00+00:00",
            "updated_at": "2026-10-01T10:00:00+00:00"
          }
        ]
        """.trimIndent()

        val rows = RemoteExpenseRow.list(Json.parseToJsonElement(json))

        assertEquals(2, rows.size)
        assertEquals(id, rows[0].id)
        // Exact decimal, never routed through Double.
        assertEquals(BigDecimal("12.34"), rows[0].amount)
        assertEquals("Lunch", rows[0].note)
        assertEquals(BigDecimal("5"), rows[1].amount)
        assertNull(rows[1].note)
    }

    @Test
    fun `a decimal that a double cannot represent survives decoding`() {
        val id = UUID.randomUUID()
        val json = """
        [{
          "id": "$id",
          "user_id": "owner-a",
          "amount": "0.1",
          "category_name": "Food",
          "note": null,
          "date": "2026-10-01T10:00:00Z",
          "created_at": "2026-10-01T10:00:00Z",
          "updated_at": "2026-10-01T10:00:00Z"
        }]
        """.trimIndent()

        val row = RemoteExpenseRow.list(Json.parseToJsonElement(json)).single()
        assertEquals(BigDecimal("0.1"), row.amount)
        assertEquals("0.1", row.amount.toPlainString())
    }

    @Test
    fun `timestamps parse with fractional seconds, Z, and space separators`() {
        assertNotNull(Timestamps.parse("2026-10-01T10:00:00+00:00"))
        assertNotNull(Timestamps.parse("2026-10-01T10:00:00.123456+00:00"))
        assertNotNull(Timestamps.parse("2026-10-01T10:00:00Z"))
        assertNotNull(Timestamps.parse("2026-10-01 10:00:00+00:00"))
        assertEquals(
            "a value with no offset is treated as UTC",
            Timestamps.parse("2026-10-01T10:00:00Z"),
            Timestamps.parse("2026-10-01T10:00:00")
        )
    }

    @Test
    fun `an unparseable timestamp returns null rather than a wrong date`() {
        assertNull(Timestamps.parse("not-a-date"))
    }
}
