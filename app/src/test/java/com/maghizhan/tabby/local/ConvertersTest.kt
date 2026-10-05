package com.maghizhan.tabby.local

import com.maghizhan.tabby.data.local.Converters
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Precision regressions.
 *
 * Milliseconds were not enough: Postgres `timestamptz` keeps microseconds and
 * PostgREST emits them, so truncating on the way into Room made every pulled row
 * compare as strictly newer than its local copy. Last-writer-wins then re-applied
 * the same remote row on every cycle and local edits lost to stale data.
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `microsecond precision survives a Room round trip`() {
        val instant = Instant.parse("2026-01-02T03:04:05.123456Z")

        val stored = converters.instantToEpochMicros(instant)
        val restored = converters.epochMicrosToInstant(stored)

        assertEquals(instant, restored)
        assertEquals("microseconds must not be truncated", 123_456_000, restored!!.nano)
    }

    @Test
    fun `a timestamp that differs only in microseconds is not flattened`() {
        // The exact shape of the bug: two rows one microsecond apart must stay
        // distinguishable, or conflict resolution cannot order them.
        val earlier = Instant.parse("2026-01-02T03:04:05.123456Z")
        val later = Instant.parse("2026-01-02T03:04:05.123457Z")

        val a = converters.instantToEpochMicros(earlier)!!
        val b = converters.instantToEpochMicros(later)!!

        assertEquals("the two must differ by exactly one microsecond", 1L, b - a)
    }

    @Test
    fun `pre-epoch timestamps round trip without shifting a second`() {
        // floorDiv/floorMod rather than truncating division: toward-zero rounding
        // moves a pre-1970 value by a full second.
        val instant = Instant.parse("1969-07-20T20:17:40.000001Z")
        assertEquals(instant, converters.epochMicrosToInstant(converters.instantToEpochMicros(instant)))
    }

    @Test
    fun `money round trips exactly, including values a double cannot hold`() {
        val value = BigDecimal("0.10")
        val restored = converters.stringToDecimal(converters.decimalToString(value))

        assertEquals(value, restored)
        assertEquals("0.10", converters.decimalToString(value))
    }

    @Test
    fun `large amounts keep every digit`() {
        val value = BigDecimal("9999999999.99")
        assertEquals(value, converters.stringToDecimal(converters.decimalToString(value)))
    }

    @Test
    fun `nulls pass through`() {
        assertEquals(null, converters.instantToEpochMicros(null))
        assertEquals(null, converters.epochMicrosToInstant(null))
        assertEquals(null, converters.decimalToString(null))
        assertEquals(null, converters.stringToDecimal(null))
    }
}
