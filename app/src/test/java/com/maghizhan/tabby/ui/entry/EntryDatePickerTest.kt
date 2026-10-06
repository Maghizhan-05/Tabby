package com.maghizhan.tabby.ui.entry

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Material's date picker interprets `initialSelectedDateMillis` as a UTC calendar
 * date.
 *
 * The defect: the expense's raw instant was passed straight in, so whenever the
 * local offset pushed that instant across a UTC boundary the picker opened on the
 * wrong day — the day before, east of UTC early in the morning; the day after,
 * west of UTC late in the evening. The conversion is now explicit and lives in
 * [EntryForm] so it can be tested without a device.
 */
class EntryDatePickerTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")      // UTC+05:30
    private val losAngeles = ZoneId.of("America/Los_Angeles") // UTC-08:00
    private val chatham = ZoneId.of("Pacific/Chatham")    // UTC+12:45

    private fun pickerDate(instant: Instant, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(EntryForm.datePickerInitialMillis(instant, zone))
            .atZone(ZoneOffset.UTC)
            .toLocalDate()

    @Test
    fun `the picker opens on the local day east of UTC in the early morning`() {
        // 02:00 local on the 15th in Kolkata is 20:30 UTC on the 14th. The raw
        // instant would have preselected the 14th.
        val instant = LocalDate.of(2026, 1, 15)
            .atTime(2, 0)
            .atZone(kolkata)
            .toInstant()

        assertEquals(LocalDate.of(2026, 1, 15), pickerDate(instant, kolkata))
    }

    @Test
    fun `the picker opens on the local day west of UTC late in the evening`() {
        // 22:00 local on the 15th in Los Angeles is 06:00 UTC on the 16th. The
        // raw instant would have preselected the 16th.
        val instant = LocalDate.of(2026, 1, 15)
            .atTime(22, 0)
            .atZone(losAngeles)
            .toInstant()

        assertEquals(LocalDate.of(2026, 1, 15), pickerDate(instant, losAngeles))
    }

    @Test
    fun `a 45-minute offset zone is handled too`() {
        val instant = LocalDate.of(2026, 1, 15)
            .atTime(1, 0)
            .atZone(chatham)
            .toInstant()

        assertEquals(LocalDate.of(2026, 1, 15), pickerDate(instant, chatham))
    }

    @Test
    fun `midday is unaffected, as it always was`() {
        val instant = LocalDate.of(2026, 1, 15).atTime(12, 0).atZone(kolkata).toInstant()
        assertEquals(LocalDate.of(2026, 1, 15), pickerDate(instant, kolkata))
    }

    @Test
    fun `confirming a picked date keeps the existing time of day`() {
        val current = LocalDate.of(2026, 1, 15).atTime(21, 37).atZone(kolkata).toInstant()
        val picked = LocalDate.of(2026, 1, 9)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        val result = EntryForm.instantFromPickedDate(picked, current, kolkata)
        val local = result.atZone(kolkata)

        assertEquals(LocalDate.of(2026, 1, 9), local.toLocalDate())
        // Choosing a date must not silently reset the clock to 00:00.
        assertEquals(21, local.hour)
        assertEquals(37, local.minute)
    }

    @Test
    fun `an unchanged round trip returns the same local day and time`() {
        val current = LocalDate.of(2026, 1, 15).atTime(23, 10).atZone(losAngeles).toInstant()

        val reselected = EntryForm.instantFromPickedDate(
            EntryForm.datePickerInitialMillis(current, losAngeles),
            current,
            losAngeles
        )

        assertEquals(
            "opening and confirming without changing anything must be a no-op",
            current.atZone(losAngeles).toLocalDateTime(),
            reselected.atZone(losAngeles).toLocalDateTime()
        )
    }
}
