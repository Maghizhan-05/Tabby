package com.maghizhan.tabby.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maghizhan.tabby.data.local.CategoryStore
import com.maghizhan.tabby.data.local.entity.CategoryEntity
import com.maghizhan.tabby.data.sync.Ownership
import com.maghizhan.tabby.data.sync.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Pure category-management rules, ported from `ProfileViewModel.swift`.
 *
 * The visibility and deletability rules decide whether a user can destroy a
 * shared default or another account's row, so they are plain functions with
 * unit tests rather than behaviour buried in a screen.
 */
object CategoryRules {

    /**
     * Categories the account may see: the shared seeded defaults plus its own
     * custom ones, excluding anything pending deletion.
     *
     * Note that defaults are visible even when signed out — they carry no owner
     * and exist before any account does.
     */
    fun visibleCategories(
        categories: List<CategoryEntity>,
        activeOwnerId: String?
    ): List<CategoryEntity> {
        val owner = Ownership.normalized(activeOwnerId)
        return categories.filter { category ->
            if (category.syncState == SyncState.DELETED) return@filter false
            if (category.isDefault) return@filter true
            if (owner == null) return@filter false
            Ownership.normalized(category.ownerId) == owner
        }
    }

    /**
     * A seeded default is never deletable, by anyone.
     *
     * It is shared across accounts with no owner, so "delete" would either
     * remove it for everybody or claim it for the deleter — and the store
     * already refuses to let one account own it.
     */
    fun canDelete(category: CategoryEntity, activeOwnerId: String?): Boolean {
        if (category.isDefault) return false
        val owner = Ownership.normalized(activeOwnerId) ?: return false
        return Ownership.normalized(category.ownerId) == owner
    }

    /** True when [name] already exists among what this account can see. */
    fun isDuplicate(
        categories: List<CategoryEntity>,
        name: String,
        activeOwnerId: String?
    ): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return visibleCategories(categories, activeOwnerId)
            .any { it.name.equals(trimmed, ignoreCase = true) }
    }
}

data class ProfileUiState(
    val newCategoryName: String = "",
    val notice: String? = null
)

class ProfileViewModel(
    private val categoryStore: CategoryStore,
    private val onLocalWrite: suspend () -> Unit
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun onNewCategoryNameChanged(value: String) =
        _uiState.update { it.copy(newCategoryName = value, notice = null) }

    fun addCategory(categories: List<CategoryEntity>, activeOwnerId: String?) {
        val trimmed = _uiState.value.newCategoryName.trim()
        if (trimmed.isEmpty()) return

        val owner = Ownership.normalized(activeOwnerId)
        if (owner == null) {
            _uiState.update { it.copy(notice = "Sign in to add categories.") }
            return
        }
        if (CategoryRules.isDuplicate(categories, trimmed, owner)) {
            _uiState.update { it.copy(notice = "\"$trimmed\" already exists.") }
            return
        }

        viewModelScope.launch {
            try {
                categoryStore.save(
                    CategoryEntity(
                        id = UUID.randomUUID(),
                        name = trimmed,
                        isDefault = false,
                        sortOrder = (
                            CategoryRules.visibleCategories(categories, owner)
                                .maxOfOrNull { it.sortOrder } ?: 0
                            ) + 1,
                        syncStateRaw = SyncState.LOCAL.raw,
                        remoteId = null,
                        ownerId = owner
                    ),
                    owner
                )
                _uiState.value = ProfileUiState()
                onLocalWrite()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update { it.copy(notice = error.message ?: "Could not add. Try again.") }
            }
        }
    }

    /**
     * Deletes a custom category by tombstoning it.
     *
     * Always a tombstone, never a local erase — even for a row that was never
     * pushed. The coordinator pushes DELETED rows as explicit remote deletes and
     * only then reaps them, so a never-pushed row's delete is a harmless no-op
     * followed by removal, while erasing it here would race a pull that still
     * carries it and resurrect the category in the picker.
     */
    companion object {
        fun factory(
            categoryStore: CategoryStore,
            onLocalWrite: suspend () -> Unit
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(categoryStore, onLocalWrite) }
        }
    }

    fun deleteCategory(category: CategoryEntity, activeOwnerId: String?) {
        if (!CategoryRules.canDelete(category, activeOwnerId)) return
        val owner = Ownership.normalized(activeOwnerId) ?: return

        viewModelScope.launch {
            try {
                categoryStore.save(
                    category.copy(syncStateRaw = SyncState.DELETED.raw),
                    owner
                )
                onLocalWrite()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.update { it.copy(notice = error.message ?: "Could not delete.") }
            }
        }
    }
}
