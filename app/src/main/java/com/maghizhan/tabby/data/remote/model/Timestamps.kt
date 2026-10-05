package com.maghizhan.tabby.data.remote.model

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Parses the timestamp shapes PostgREST emits, ported from the iOS
 * `RemoteExpenseRow.parseTimestamp`: with or without fractional seconds, and
 * with a `+00:00`, `Z`, or space-separated offset. A value with no offset at all
 * is Postgres `timestamptz` returned in UTC.
 *
 * Returns null rather than throwing so callers can raise a decode error that
 * names the offending field, exactly as the Swift version does.
 */
object Timestamps {

    fun parse(text: String): Instant? {
        val normalized = text.replace(" ", "T")
        // Handles both fractional and non-fractional seconds when an offset is present.
        runCatching { return OffsetDateTime.parse(normalized).toInstant() }
        runCatching {
            return OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        }
        // No explicit offset: treat as UTC.
        return try {
            OffsetDateTime.parse(normalized + "Z").toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
