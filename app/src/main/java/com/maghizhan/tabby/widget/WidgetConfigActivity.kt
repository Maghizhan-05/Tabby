package com.maghizhan.tabby.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.maghizhan.tabby.ui.common.TabbyCard
import com.maghizhan.tabby.ui.theme.Tabby
import com.maghizhan.tabby.ui.theme.TabbyBackdrop
import com.maghizhan.tabby.ui.theme.TabbyShapes
import com.maghizhan.tabby.ui.theme.TabbyTheme
import kotlinx.coroutines.launch

/**
 * Chooses a placed widget's period and what it displays.
 *
 * Android has no equivalent of the iOS widget configuration intent; the closest
 * thing is an activity the launcher starts when the widget is dropped, declared
 * via `android:configure` in the provider XML. Tapping the header still cycles
 * the mode afterwards, so the activity adds a way to configure WITHOUT removing
 * the one that already existed.
 *
 * ## The two contract rules that make this safe
 *
 * 1. **The result is CANCELED until the user saves.** It is set in [onCreate]
 *    before anything else, so backing out — or the process dying mid-dialog —
 *    leaves the launcher to drop the pending widget rather than placing a
 *    half-configured one.
 * 2. **State is written before the OK result is returned.** Glance's state is
 *    suspending, so the result is delivered from inside the coroutine after the
 *    write and the update complete. Returning first would race the launcher's
 *    first render against the preferences write and show defaults.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Rule 1: canceled by default, set BEFORE the UI exists so every exit
        // path that is not an explicit save leaves no widget behind.
        setResult(Activity.RESULT_CANCELED, resultIntent())

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            // Launched outside the widget-placement flow; there is nothing to
            // configure and no id to write against.
            finish()
            return
        }

        setContent {
            TabbyTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    TabbyBackdrop()
                    ConfigScreen(onSave = ::save)
                }
            }
        }
    }

    private fun save(config: WidgetConfig) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity)
                .getGlanceIdBy(appWidgetId)

            updateAppWidgetState(this@WidgetConfigActivity, glanceId) { preferences ->
                preferences[WIDGET_MODE_KEY] = config.mode.name
                preferences[WIDGET_SHOW_RING_KEY] = config.showRing
                preferences[WIDGET_SHOW_BREAKDOWN_KEY] = config.showBreakdown
            }
            // Render once with the chosen settings before handing control back,
            // so the widget never appears with defaults and then visibly
            // changes a moment later.
            TabbyWidget().update(this@WidgetConfigActivity, glanceId)

            // Rule 2: only now is the placement accepted.
            setResult(Activity.RESULT_OK, resultIntent())
            finish()
        }
    }

    /** Every result must echo the id, or the launcher cannot match it. */
    private fun resultIntent(): Intent =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

@Composable
private fun ConfigScreen(onSave: (WidgetConfig) -> Unit) {
    val colors = Tabby.colors
    var mode by remember { mutableStateOf(WidgetMode.DAY) }
    var showRing by remember { mutableStateOf(true) }
    var showBreakdown by remember { mutableStateOf(true) }

    // safeDrawing, not a bare padding: the title sat under the status bar
    // without it, and the save button fell off the bottom of a short screen --
    // which made the widget impossible to place at all, since the launcher
    // only commits the placement when this activity returns RESULT_OK.
    //
    // The content scrolls for the same reason: with four periods, two toggles
    // and a note, a small or large-font-scale device cannot show the button
    // without it, and an unreachable save button is a dead end rather than a
    // cosmetic flaw.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Widget",
            color = colors.ink,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold
        )

        TabbyCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "PERIOD",
                    color = colors.subtleInk,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                WidgetMode.entries.forEach { candidate ->
                    val selected = candidate == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(TabbyShapes.control)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { mode = candidate }
                            )
                            .background(
                                if (selected) colors.accent.copy(alpha = 0.16f) else Color.Transparent
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (selected) colors.accent else colors.hairline
                                )
                                .padding(5.dp)
                        ) {}
                        Text(
                            text = candidate.title,
                            color = if (selected) colors.ink else colors.subtleInk,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                }
            }
        }

        TabbyCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ConfigToggle(
                    label = "Show ring",
                    checked = showRing,
                    onCheckedChange = { showRing = it }
                )
                ConfigToggle(
                    label = "Show breakdown",
                    checked = showBreakdown,
                    onCheckedChange = { showBreakdown = it }
                )
                Text(
                    // Stated rather than silently overridden: a user who turns
                    // the breakdown on and then resizes to a square cell would
                    // otherwise think the setting had been ignored.
                    text = "A square widget always shows the ring and amount only.",
                    color = colors.subtleInk,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        Button(
            onClick = { onSave(WidgetConfig(mode, showRing, showBreakdown)) },
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.paper
            ),
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Add widget", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ConfigToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = Tabby.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = colors.ink,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.paper,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.subtleInk,
                uncheckedTrackColor = colors.elevatedSurface
            )
        )
    }
}
