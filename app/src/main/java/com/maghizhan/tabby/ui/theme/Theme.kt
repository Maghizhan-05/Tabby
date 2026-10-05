package com.maghizhan.tabby.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Placeholder dark scheme. The full palette port of the iOS `Theme.swift`
 * (ink/paper/surface/accent gold) lands with the UI checkpoint.
 */
private val TabbyDarkColors = darkColorScheme()

@Composable
fun TabbyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TabbyDarkColors,
        content = content
    )
}
