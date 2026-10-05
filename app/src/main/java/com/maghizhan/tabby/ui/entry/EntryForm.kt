package com.maghizhan.tabby.ui.entry

import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import java.math.BigDecimal

/**
 * The shared, UI-free core of quick entry and expense editing.
 *
 * Both sheets ask the same four questions — is the amount usable, is a category
 * chosen, is the note within its cap, and what canonical category name should be
 * stored — so the answers live in one place. The iOS app duplicated this logic
 * across `QuickEntryViewModel` and `ExpenseEditViewModel` and the two drifted
 * (the edit form never validated the note length); a single object cannot drift.
 *
 * Kept free of Android, Compose and Room imports so it is unit-testable.
 */
object EntryForm {

    /**
     * Parses a typed amount, or null when it is not a usable positive amount.
     *
     * A comma is accepted as the decimal separator (locale keyboards emit it),
     * mirroring the iOS `replacingOccurrences(of: ",", with: ".")`. Parsing is
     * exact `BigDecimal`: going through `Double` would make 0.07 unrepresentable
     * and the stored amount would differ from what the user typed.
     *
     * Over-scale input (three or more decimals) is rejected HERE rather than
     * left to the storage layer, where `MoneyValidation` throws — a thrown
     * exception at save time would surface as a crash or an opaque failure
     * instead of a disabled submit button.
     */
    fun parseAmount(text: String): BigDecimal? {
        val cleaned = text.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val value = runCatching { BigDecimal(cleaned) }.getOrNull() ?: return null
        if (value.signum() <= 0) return null
        if (value.stripTrailingZeros().scale() > 2) return null
        return value
    }

    /** True while the note is within the shared 120-character cap. */
    fun isNoteValid(note: String): Boolean =
        note.length <= ExpenseEntity.MAXIMUM_NOTE_LENGTH

    /** Clamps a note to the cap, so a paste cannot exceed it. */
    fun clampNote(note: String): String =
        if (note.length <= ExpenseEntity.MAXIMUM_NOTE_LENGTH) {
            note
        } else {
            note.take(ExpenseEntity.MAXIMUM_NOTE_LENGTH)
        }

    fun canSubmit(amountText: String, categoryQuery: String, note: String): Boolean =
        parseAmount(amountText) != null &&
            categoryQuery.trim().isNotEmpty() &&
            isNoteValid(note)

    /** Categories matching [query], case-insensitively; everything when blank. */
    fun filtered(categories: List<CategoryEntity>, query: String): List<CategoryEntity> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return categories
        return categories.filter { it.name.contains(trimmed, ignoreCase = true) }
    }

    /** True when [query] matches no existing category exactly (so it would be created). */
    fun isNewCategory(categories: List<CategoryEntity>, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return false
        return categories.none { it.name.equals(trimmed, ignoreCase = true) }
    }

    /**
     * The canonical name to store for [query].
     *
     * An existing category's own spelling wins over whatever the user typed:
     * storing "food" alongside "Food" would split one category into two in every
     * chart and breakdown, since expenses reference categories by name.
     */
    fun canonicalCategoryName(categories: List<CategoryEntity>, query: String): String {
        val trimmed = query.trim()
        return categories.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.name ?: trimmed
    }

    /** The sort order a newly created category should take. */
    fun nextSortOrder(categories: List<CategoryEntity>): Int =
        (categories.maxOfOrNull { it.sortOrder } ?: 0) + 1
}
