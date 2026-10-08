package com.maghizhan.tabby.ui

import com.maghizhan.tabby.data.local.DefaultCategories
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.sync.SyncState
import com.maghizhan.tabby.ui.common.AmountTypography
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

    @Test
    fun `signed stays in rupees regardless of device locale`() {
        // iOS puts every Friends amount through WidgetCurrencyFormatter, so the
        // net must not switch to the device's own currency: a "+$300" net beside
        // "₹500" and "₹200" columns is three currencies in one row.
        assertTrue(CurrencyFormat.signed(BigDecimal("300"), Locale.US).startsWith("+₹"))
        assertTrue(CurrencyFormat.signed(BigDecimal("-300"), Locale.US).startsWith("-₹"))
        assertTrue(CurrencyFormat.signed(BigDecimal.ZERO, Locale.US).startsWith("₹"))
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

    // MARK: - Amount headline sizing

    @Test
    fun `a short amount renders at the full iOS headline size`() {
        // "₹66.91" is well within the comfortable width, so it must NOT shrink.
        assertEquals(
            AmountTypography.BASE_SP,
            AmountTypography.fontSizeSp("₹66.91".length),
            0.001f
        )
    }

    @Test
    fun `a long amount shrinks rather than clipping the user's money`() {
        val long = "₹12,34,567.89"
        val size = AmountTypography.fontSizeSp(long.length)
        assertTrue("a 13-glyph amount must step down from 40sp", size < AmountTypography.BASE_SP)
        assertTrue("and must stay legible", size >= AmountTypography.MINIMUM_SP)
    }

    @Test
    fun `the step-down never goes below the iOS half-size floor`() {
        // iOS pins minimumScaleFactor(0.5); an absurd amount must hit that floor
        // and stop, not vanish.
        assertEquals(
            AmountTypography.MINIMUM_SP,
            AmountTypography.fontSizeSp(500),
            0.001f
        )
    }

    @Test
    fun `the step-down is monotonic in length`() {
        val sizes = (1..60).map { AmountTypography.fontSizeSp(it) }
        assertTrue(
            "a longer amount must never render larger than a shorter one",
            sizes.zipWithNext().all { (a, b) -> b <= a }
        )
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
