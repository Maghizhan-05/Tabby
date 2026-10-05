package com.maghizhan.tabby.ui.entry

import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

/** The quick-entry form's validation and category resolution rules. */
class EntryFormTest {

    private fun category(name: String, sortOrder: Int = 0, isDefault: Boolean = false) =
        CategoryEntity(
            id = UUID.randomUUID(),
            name = name,
            isDefault = isDefault,
            sortOrder = sortOrder,
            syncStateRaw = SyncState.SYNCED.raw,
            remoteId = null,
            ownerId = "owner-a"
        )

    private val categories = listOf(
        category("Food", 0, isDefault = true),
        category("Transport", 1, isDefault = true),
        category("Climbing", 2)
    )

    @Test
    fun `parseAmount accepts plain and comma decimal separators`() {
        assertEquals(BigDecimal("12.50"), EntryForm.parseAmount("12.50"))
        // A comma is what an Indian or European keyboard offers; rejecting it
        // would make the form unusable on those locales.
        assertEquals(BigDecimal("12.50"), EntryForm.parseAmount("12,50"))
    }

    @Test
    fun `parseAmount trims surrounding whitespace`() {
        assertEquals(BigDecimal("7"), EntryForm.parseAmount("  7  "))
    }

    @Test
    fun `parseAmount rejects zero and negative amounts`() {
        // A spend of zero is not a spend, and a negative one would silently
        // reduce a category total below what was actually spent.
        assertNull(EntryForm.parseAmount("0"))
        assertNull(EntryForm.parseAmount("0.00"))
        assertNull(EntryForm.parseAmount("-5"))
    }

    @Test
    fun `parseAmount rejects more than two decimal places`() {
        // Money is stored at scale 2; accepting 10.005 would round at the store
        // boundary and the saved row would not match what the user typed.
        assertNull(EntryForm.parseAmount("10.005"))
        assertEquals(BigDecimal("10.00"), EntryForm.parseAmount("10.00"))
    }

    @Test
    fun `parseAmount rejects empty and non-numeric input`() {
        assertNull(EntryForm.parseAmount(""))
        assertNull(EntryForm.parseAmount("   "))
        assertNull(EntryForm.parseAmount("abc"))
        assertNull(EntryForm.parseAmount("1.2.3"))
    }

    @Test
    fun `clampNote truncates at the entity cap rather than rejecting`() {
        val long = "x".repeat(ExpenseEntity.MAXIMUM_NOTE_LENGTH + 50)
        val clamped = EntryForm.clampNote(long)
        // Truncation, not rejection: a pasted paragraph should lose its tail,
        // not the whole entry.
        assertEquals(ExpenseEntity.MAXIMUM_NOTE_LENGTH, clamped.length)
        assertTrue(EntryForm.isNoteValid(clamped))
    }

    @Test
    fun `canSubmit requires a valid amount and a category`() {
        assertFalse(EntryForm.canSubmit("", "Food", ""))
        assertFalse(EntryForm.canSubmit("10", "", ""))
        assertFalse(EntryForm.canSubmit("10", "   ", ""))
        assertFalse(EntryForm.canSubmit("0", "Food", ""))
        assertTrue(EntryForm.canSubmit("10", "Food", ""))
    }

    @Test
    fun `canonicalCategoryName reuses the stored casing of an existing category`() {
        // Expenses reference categories by name. Saving "food" alongside "Food"
        // would split one category's total across two ring slices.
        assertEquals("Food", EntryForm.canonicalCategoryName(categories, "food"))
        assertEquals("Food", EntryForm.canonicalCategoryName(categories, "  FOOD "))
    }

    @Test
    fun `canonicalCategoryName trims a genuinely new name`() {
        assertEquals("Climbing gear", EntryForm.canonicalCategoryName(categories, " Climbing gear "))
    }

    @Test
    fun `isNewCategory is false for an existing name in any casing`() {
        assertFalse(EntryForm.isNewCategory(categories, "food"))
        assertFalse(EntryForm.isNewCategory(categories, "Climbing"))
        assertTrue(EntryForm.isNewCategory(categories, "Gifts"))
    }

    @Test
    fun `isNewCategory is false for blank input so no empty category is created`() {
        assertFalse(EntryForm.isNewCategory(categories, "   "))
    }

    @Test
    fun `filtered matches on a substring case-insensitively`() {
        assertEquals(listOf("Transport"), EntryForm.filtered(categories, "ransp").map { it.name })
        assertEquals(listOf("Food"), EntryForm.filtered(categories, "FOO").map { it.name })
    }

    @Test
    fun `filtered with a blank query returns everything`() {
        assertEquals(categories.size, EntryForm.filtered(categories, "").size)
    }

    @Test
    fun `nextSortOrder appends after the highest existing order`() {
        // Highest existing is 2, so the next is 3.
        assertEquals(3, EntryForm.nextSortOrder(categories))
    }

    @Test
    fun `nextSortOrder starts at one for an empty list`() {
        // 1 rather than 0 — matches the existing implementation, and 0 is
        // reserved for the first seeded default.
        assertEquals(1, EntryForm.nextSortOrder(emptyList()))
    }
}
