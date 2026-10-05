package com.maghizhan.tabby.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The six analytics views offered by the compact selector, as on iOS. */
enum class AnalyticsMode(val label: String) {
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    YEARLY("Yearly"),
    CATEGORIES("Categories"),
    TRENDS("Trends")
}

/**
 * Holds the home screen's view-level selections.
 *
 * In a ViewModel rather than `remember` state so the chosen mode and the
 * selected ring slice survive configuration changes — rotating the device must
 * not silently reset the user back to Daily.
 */
class HomeViewModel : ViewModel() {

    private val _mode = MutableStateFlow(AnalyticsMode.DAILY)
    val mode: StateFlow<AnalyticsMode> = _mode.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    /** Changing mode clears the selection: a slice from the previous period's chart is meaningless. */
    fun onModeSelected(mode: AnalyticsMode) {
        _mode.value = mode
        _selectedCategory.value = null
    }

    fun onCategorySelected(category: String?) {
        _selectedCategory.value = category
    }
}
