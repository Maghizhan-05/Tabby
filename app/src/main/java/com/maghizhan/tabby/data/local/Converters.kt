package com.maghizhan.tabby.data.local

import androidx.room.TypeConverter
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Room converters.
 *
 * Two precision rules, both load-bearing for sync correctness:
 *
 * 1. Money is stored as its plain decimal *string*, never a Double. The iOS
 *    store uses `Decimal`, and routing currency through a binary float would
 *    silently change values that sync between the two apps.
 *
 * 2. Timestamps are stored as epoch **microseconds**, not milliseconds.
 *    Postgres `timestamptz` has microsecond resolution and PostgREST emits it
 *    (`...123456Z`). Truncating to milliseconds made a remote row compare as
 *    strictly newer than its local copy after every single pull, so
 *    last-writer-wins re-applied the same row forever and local edits lost to
 *    stale data. Microseconds match the backend exactly; a Long holds epoch
 *    microseconds until the year 294247.
 */
class Converters {

    @TypeConverter
    fun uuidToString(value: UUID?): String? = value?.toString()

    @TypeConverter
    fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)

    @TypeConverter
    fun instantToEpochMicros(value: Instant?): Long? = value?.let {
        Math.multiplyExact(it.epochSecond, MICROS_PER_SECOND) + it.nano / NANOS_PER_MICRO
    }

    @TypeConverter
    fun epochMicrosToInstant(value: Long?): Instant? = value?.let {
        // floorDiv/floorMod rather than truncating division, so a pre-epoch
        // value rounds correctly instead of shifting by a whole second.
        Instant.ofEpochSecond(
            Math.floorDiv(it, MICROS_PER_SECOND),
            Math.floorMod(it, MICROS_PER_SECOND) * NANOS_PER_MICRO
        )
    }

    @TypeConverter
    fun decimalToString(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun stringToDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val NANOS_PER_MICRO = 1_000L
    }
}
