package com.maghizhan.tabby.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * The per-instance settings a placed widget carries.
 *
 * Why per instance and not one global setting: the launcher lets the user drop
 * several Tabby widgets, and a shared setting would make configuring one of
 * them silently reconfigure the rest. Glance's [PreferencesGlanceStateDefinition]
 * is already keyed by `GlanceId`, so every key here is automatically scoped to
 * the widget that owns it — and Glance deletes that record when the instance is
 * removed, so no orphaned configuration survives an uninstall from the home
 * screen.
 *
 * Read back through [from] rather than touched key-by-key at the call sites, so
 * a widget placed before a key existed renders with defaults instead of
 * throwing or blanking.
 */
internal data class WidgetConfig(
    /** Which period the instance opens on. */
    val mode: WidgetMode = WidgetMode.DAY,
    /**
     * Whether the ring is drawn at all.
     *
     * Off turns the widget into a plain figure. Offered because at the smallest
     * cell the ring crowds the amount, and some users want the number only —
     * the same reason iOS offers more than one widget family.
     */
    val showRing: Boolean = true,
    /**
     * Whether the category breakdown is listed beside the ring.
     *
     * Independent of [showRing]: ring-only, list-only, both, or neither are all
     * coherent, and tying them together would make the narrow cell's layout a
     * special case instead of a configuration.
     */
    val showBreakdown: Boolean = true
) {
    companion object {
        fun from(preferences: Preferences): WidgetConfig = WidgetConfig(
            mode = WidgetMode.fromName(preferences[WIDGET_MODE_KEY]),
            // Defaults are applied here, not written at configure time: a
            // widget restored from a backup or placed by a launcher that skips
            // the configuration activity must still render.
            showRing = preferences[WIDGET_SHOW_RING_KEY] ?: true,
            showBreakdown = preferences[WIDGET_SHOW_BREAKDOWN_KEY] ?: true
        )
    }
}

/** Per-widget persisted mode, so each placed instance keeps its own choice. */
internal val WIDGET_MODE_KEY = stringPreferencesKey("tabby_widget_mode")

/** Per-widget: draw the category ring. */
internal val WIDGET_SHOW_RING_KEY = booleanPreferencesKey("tabby_widget_show_ring")

/** Per-widget: list the category breakdown beside the ring. */
internal val WIDGET_SHOW_BREAKDOWN_KEY = booleanPreferencesKey("tabby_widget_show_breakdown")
