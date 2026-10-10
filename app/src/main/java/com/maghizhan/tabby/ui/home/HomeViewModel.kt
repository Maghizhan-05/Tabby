package com.maghizhan.tabby.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maghizhan.tabby.analytics.AnalyticsEvent
import com.maghizhan.tabby.analytics.AnalyticsPeriod
import kotlinx.coroutines.launch
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
class HomeViewModel(
    private val onAnalytics: suspend (AnalyticsEvent) -> Unit = {}
) : ViewModel() {

    private val _mode = MutableStateFlow(AnalyticsMode.DAILY)
    val mode: StateFlow<AnalyticsMode> = _mode.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    /** Changing mode clears the selection: a slice from the previous period's chart is meaningless. */
    fun onModeSelected(mode: AnalyticsMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        _selectedCategory.value = null
        viewModelScope.launch { onAnalytics(AnalyticsEvent.PeriodChanged(mode.analyticsPeriod)) }
    }

    fun onCategorySelected(category: String?) {
        _selectedCategory.value = category
    }

    companion object {
        fun factory(onAnalytics: suspend (AnalyticsEvent) -> Unit): ViewModelProvider.Factory =
            viewModelFactory { initializer { HomeViewModel(onAnalytics) } }
    }
}

private val AnalyticsMode.analyticsPeriod: AnalyticsPeriod
    get() = when (this) {
        AnalyticsMode.DAILY -> AnalyticsPeriod.TODAY
        AnalyticsMode.WEEKLY -> AnalyticsPeriod.WEEK
        AnalyticsMode.MONTHLY -> AnalyticsPeriod.MONTH
        AnalyticsMode.YEARLY -> AnalyticsPeriod.YEAR
        AnalyticsMode.CATEGORIES -> AnalyticsPeriod.CATEGORY
        AnalyticsMode.TRENDS -> AnalyticsPeriod.TREND
    }
