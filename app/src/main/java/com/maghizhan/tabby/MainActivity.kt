package com.maghizhan.tabby

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maghizhan.tabby.ui.theme.TabbyTheme

/**
 * Checkpoint 1 scaffold only. Proves the toolchain builds, installs and runs;
 * the real screens arrive in the later approved checkpoints.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TabbyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScaffoldPlaceholder()
                }
            }
        }
    }
}

@Composable
private fun ScaffoldPlaceholder() {
    Column(modifier = Modifier.padding(24.dp)) {
        Text(text = "Tabby", style = MaterialTheme.typography.displaySmall)
        Text(
            text = "Android scaffold - checkpoint 1",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
