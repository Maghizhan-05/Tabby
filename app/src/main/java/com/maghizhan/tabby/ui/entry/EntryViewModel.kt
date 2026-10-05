package com.maghizhan.tabby.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maghizhan.tabby.data.local.CategoryStore
import com.maghizhan.tabby.data.local.ExpenseStore
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.local.entity.ExpenseEntity
import com.maghizhan.tabby.data.sync.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/** Everything the quick-entry / edit sheet renders. */
data class EntryUiState(
    val amountText: String = "",
    val categoryQuery: String = "",
    val noteText: String = "",
    val date: Instant = Instant.now(),
    val notice: String? = null,
    val isSaving: Boolean = false,
    /** Set once a save has durably committed, so the sheet can dismiss itself. */
    val saved: Boolean = false
) {
    val canSubmit: Boolean
        get() = !isSaving && EntryForm.canSubmit(amountText, categoryQuery, noteText)

    val isNoteValid: Boolean get() = EntryForm.isNoteValid(noteText)
}

/**
 * Backs both the quick-entry sheet and the edit sheet.
 *
 * One view model rather than two: the only difference between logging and
 * editing a spend is whether an id and a prior revision already exist, and
 * `ExpenseStore.save` is an upsert that handles both. Two classes would mean two
 * places to keep the owner check, the note cap and the sync trigger correct.
 *
 * Saving goes through [ExpenseStore], never a raw DAO upsert, so the
 * authorise-then-persist transaction (foreign-owner refusal, money
 * normalisation, revision bump) applies to user writes exactly as it does to the
 * sync coordinator's.
 */
class EntryViewModel(
    private val expenseStore: ExpenseStore,
    private val categoryStore: CategoryStore,
    private val onLocalWrite: suspend () -> Unit,
    private val now: () -> Instant = Instant::now
) : ViewModel() {

    /**
     * The row being edited, or null when logging a new spend.
     *
     * Mutable rather than a constructor parameter so ONE view model instance
     * serves every opening of the sheet. A per-expense instance would be
     * re-created on each tap, and anything the user had typed into a sheet that
     * was dismissed and reopened would survive in the previous instance — the
     * classic "edit one row, see another row's amount" bug.
     */
    private var existing: ExpenseEntity? = null

    private val _uiState = MutableStateFlow(EntryUiState(date = now()))
    val uiState: StateFlow<EntryUiState> = _uiState.asStateFlow()

    /** Resets to a blank form for a new spend. */
    fun startNew() {
        existing = null
        _uiState.value = EntryUiState(date = now())
    }

    /** Loads an existing row's values into the form. */
    fun startEditing(expense: ExpenseEntity) {
        existing = expense
        _uiState.value = EntryUiState(
            amountText = expense.amount.toPlainString(),
            categoryQuery = expense.categoryName,
            noteText = expense.note ?: "",
            date = expense.date
        )
    }

    /**
     * Clears the `saved` flag after the sheet has acted on it.
     *
     * Needed because `saved` is a latch, not an event: without clearing it the
     * next opening of the sheet would observe a stale `true` and dismiss itself
     * before the user could type.
     */
    fun consumeSaved() = _uiState.update { it.copy(saved = false) }

    /**
     * Tombstones a spend, then triggers a sync.
     *
     * Goes through [ExpenseStore.markDeleted] rather than a delete query so the
     * owner check and the tombstone-versus-erase decision stay in one place —
     * the same path the sync coordinator relies on to not resurrect the row.
     */
    fun delete(expense: ExpenseEntity, activeOwnerId: String?) {
        val owner = activeOwnerId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            try {
                expenseStore.markDeleted(expense, owner)
                onLocalWrite()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(notice = error.message ?: "Could not delete. Try again.")
                }
            }
        }
    }

    fun onAmountChanged(text: String) = _uiState.update { it.copy(amountText = text, notice = null) }

    fun onCategoryChanged(text: String) =
        _uiState.update { it.copy(categoryQuery = text, notice = null) }

    /** Clamped on the way in, so a paste longer than the cap is truncated, not rejected. */
    fun onNoteChanged(text: String) =
        _uiState.update { it.copy(noteText = EntryForm.clampNote(text), notice = null) }

    fun onDateChanged(date: Instant) = _uiState.update { it.copy(date = date, notice = null) }

    /**
     * Validates, resolves (or creates) the category, saves, and only then
     * triggers a sync.
     *
     * The category is created BEFORE the expense and in its own store call: an
     * expense references its category by name, and a row whose category does not
     * exist locally would render with no accent and vanish from the breakdown
     * until the next pull.
     */
    fun submit(categories: List<CategoryEntity>, activeOwnerId: String?) {
        val state = _uiState.value
        val amount = EntryForm.parseAmount(state.amountText)
        if (amount == null) {
            _uiState.update { it.copy(notice = "Enter a valid amount (up to 2 decimals).") }
            return
        }
        if (state.categoryQuery.trim().isEmpty()) {
            _uiState.update { it.copy(notice = "Choose a category.") }
            return
        }
        val owner = activeOwnerId?.trim()?.takeIf { it.isNotEmpty() }
        if (owner == null) {
            _uiState.update { it.copy(notice = "Sign in to save this spend.") }
            return
        }
        if (state.isSaving) return

        _uiState.update { it.copy(isSaving = true, notice = null) }

        // Read once into a local: `existing` is mutable (the sheet is reused for
        // the next row), so re-reading it inside the coroutine could mix a new
        // form's values into the previously edited row.
        val edited = existing

        viewModelScope.launch {
            try {
                val categoryName = resolveCategory(categories, state.categoryQuery, owner)
                val timestamp = now()
                val entity = edited?.copy(
                    amount = amount,
                    categoryName = categoryName,
                    note = state.noteText,
                    date = state.date,
                    updatedAt = timestamp,
                    // A synced row becomes DIRTY so the edit is pushed; a row
                    // that was never pushed stays LOCAL. Forcing DIRTY here
                    // would claim a remote counterpart exists.
                    syncStateRaw = if (edited.syncState == SyncState.SYNCED) {
                        SyncState.DIRTY.raw
                    } else {
                        edited.syncStateRaw
                    }
                ) ?: ExpenseEntity(
                    id = UUID.randomUUID(),
                    amount = amount,
                    categoryName = categoryName,
                    note = state.noteText,
                    date = state.date,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    syncStateRaw = SyncState.LOCAL.raw,
                    remoteId = null,
                    ownerId = owner
                )

                expenseStore.save(entity, owner)
                _uiState.update { it.copy(isSaving = false, saved = true, notice = null) }

                // After the local commit, never before: a failed push must leave
                // a saved row pending, not lose the user's entry.
                onLocalWrite()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        notice = error.message ?: "Could not save. Try again."
                    )
                }
            }
        }
    }

    companion object {
        /**
         * Builds instances with the stores and sync trigger injected.
         *
         * A factory rather than a no-arg view model: the stores come from
         * [com.maghizhan.tabby.AppGraph], and reaching for a global inside the
         * view model is what makes these classes untestable.
         */
        fun factory(
            expenseStore: ExpenseStore,
            categoryStore: CategoryStore,
            onLocalWrite: suspend () -> Unit
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { EntryViewModel(expenseStore, categoryStore, onLocalWrite) }
        }
    }

    private suspend fun resolveCategory(
        categories: List<CategoryEntity>,
        query: String,
        owner: String
    ): String {
        val canonical = EntryForm.canonicalCategoryName(categories, query)
        if (!EntryForm.isNewCategory(categories, query)) return canonical

        categoryStore.save(
            CategoryEntity(
                id = UUID.randomUUID(),
                name = canonical,
                isDefault = false,
                sortOrder = EntryForm.nextSortOrder(categories),
                syncStateRaw = SyncState.LOCAL.raw,
                remoteId = null,
                ownerId = owner
            ),
            owner
        )
        return canonical
    }
}
