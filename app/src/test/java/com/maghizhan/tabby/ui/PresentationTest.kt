package com.maghizhan.tabby.ui

import com.maghizhan.tabby.data.local.DefaultCategories
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.sync.SyncState
import com.maghizhan.tabby.ui.format.CurrencyFormat
import com.maghizhan.tabby.ui.home.recentEntryTitle
import com.maghizhan.tabby.ui.theme.CategoryAccent
import com.maghizhan.tabby.ui.theme.TabbyPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Presentation rules that are shared between the app and the widget. */
class PresentationTest {

    private fun expense(note: String?, category: String = "Food") = ExpenseEntity(
        id = UUID.randomUUID(),
        amount = BigDecimal("1"),
        categoryName = category,
        note = note,
        date = Instant.EPOCH,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        syncStateRaw = SyncState.SYNCED.raw,
        remoteId = null,
        ownerId = "owner-a"
    )

    // MARK: - Row titles

    @Test
    fun `a note is the row title when present`() {
        assertEquals("Coffee with Sam", recentEntryTitle(expense("Coffee with Sam")))
    }

    @Test
    fun `a missing or blank note falls back to the category`() {
        // Blank must fall back too, not render an empty row.
        assertEquals("Food expense", recentEntryTitle(expense(null)))
        assertEquals("Food expense", recentEntryTitle(expense("")))
        assertEquals("Food expense", recentEntryTitle(expense("   ")))
    }

    // MARK: - Currency

    @Test
    fun `compact leaves amounts under ten thousand unabbreviated`() {
        assertEquals("₹9,999", CurrencyFormat.compact(BigDecimal("9999")))
    }

    @Test
    fun `compact abbreviates thousands and drops a trailing zero decimal`() {
        assertEquals("₹12K", CurrencyFormat.compact(BigDecimal("12000")))
        assertEquals("₹12.5K", CurrencyFormat.compact(BigDecimal("12500")))
    }

    @Test
    fun `compact abbreviates millions and caps at the iOS ceiling`() {
        assertEquals("₹1M", CurrencyFormat.compact(BigDecimal("1000000")))
        // The ceiling matches WidgetCurrencyFormatter.swift exactly; a
        // different one would make the two platforms' widgets disagree.
        assertEquals("₹999.9M+", CurrencyFormat.compact(BigDecimal("999999999999")))
    }

    @Test
    fun `compact keeps the sign on a negative amount`() {
        assertTrue(CurrencyFormat.compact(BigDecimal("-12000")).startsWith("-"))
    }

    @Test
    fun `signed marks a positive net explicitly`() {
        // Sign, not colour: colour alone cannot convey direction to a
        // colour-blind user.
        assertTrue(CurrencyFormat.signed(BigDecimal("50"), Locale.US).startsWith("+"))
        assertTrue(CurrencyFormat.signed(BigDecimal("-50"), Locale.US).startsWith("-"))
        val zero = CurrencyFormat.signed(BigDecimal.ZERO, Locale.US)
        assertTrue(!zero.startsWith("+") && !zero.startsWith("-"))
    }

    // MARK: - Category accents

    @Test
    fun `category accent is stable for the same name`() {
        assertEquals(
            CategoryAccent.forCategory("Groceries"),
            CategoryAccent.forCategory("Groceries")
        )
    }

    @Test
    fun `category accent ignores casing and whitespace`() {
        // Otherwise "Food" and "food " would draw in two different colours.
        assertEquals(
            CategoryAccent.forCategory("Food"),
            CategoryAccent.forCategory("  FOOD ")
        )
    }

    @Test
    fun `the seeded defaults do not all collapse onto one accent`() {
        val distinct = DefaultCategories.NAMES
            .map { CategoryAccent.forCategory(it) }
            .distinct()
        assertTrue(
            "defaults mapped to only ${distinct.size} accent(s)",
            distinct.size >= 4
        )
    }

    @Test
    fun `the ring palette has the seven iOS colours`() {
        assertEquals(7, TabbyPalette.ringColors.size)
        assertEquals(7, TabbyPalette.ringColors.distinct().size)
    }

    @Test
    fun `accent and ink are distinguishable`() {
        assertNotEquals(TabbyPalette.ink, TabbyPalette.accent)
    }

    // MARK: - Seeded defaults

    @Test
    fun `the default category list matches iOS exactly`() {
        // Expenses reference categories by NAME, so a divergent list here means
        // a spend logged on iOS shows as uncategorised on Android.
        assertEquals(
            listOf(
                "Food", "Transport", "Groceries", "Bills",
                "Shopping", "Entertainment", "Health", "Other"
            ),
            DefaultCategories.NAMES
        )
    }
}
