package com.maghizhan.tabby.data.local

import androidx.room.TypeConverter
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Room converters.
 *
 * Money is stored as its plain decimal *string*, never a Double: the iOS store
 * uses `Decimal`, and routing currency through a binary float would silently
 * change values that sync between the two apps.
 */
class Converters {

    @TypeConverter
    fun uuidToString(value: UUID?): String? = value?.toString()

    @TypeConverter
    fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)

    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun decimalToString(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun stringToDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)
}
