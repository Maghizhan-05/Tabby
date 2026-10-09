package com.maghizhan.tabby.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tabby's dark, wealth-forward design system — a 1:1 port of the iOS
 * `Theme.swift`.
 *
 * Why a composition local rather than only a Material 3 `darkColorScheme`:
 * Material's scheme has no slot for `accentGlow` or for the seven ring colours,
 * and mapping them onto unrelated roles (tertiary, surfaceVariant…) would make
 * every usage site lie about what the colour means. The scheme is still provided
 * for Material components that need it; the Tabby-specific tokens live here, and
 * the SAME literal RGB values are used on both platforms so the two apps are
 * visually identical.
 *
 * Gold is reserved for focus, progress and confirmation so it reads as a signal
 * rather than decoration (the iOS rule, carried over verbatim).
 */
@Immutable
data class TabbyColors(
    val ink: Color,
    val paper: Color,
    val surface: Color,
    val elevatedSurface: Color,
    val subtleInk: Color,
    val hairline: Color,
    val accent: Color,
    val accentBright: Color,
    val accentDim: Color,
    val accentGlow: Color,
    /**
     * Money owed outward / a negative net.
     *
     * A named token rather than reaching for `Color.Red`: it is the ONE place
     * the palette says "this number is against you", it is shared by Friends'
     * rows and its aggregate footer, and it matches the iOS
     * `.red.opacity(0.82)` exactly.
     */
    val negative: Color,
    val ringColors: List<Color>
)

/** Fixed, muted accents keep category labels distinct without breaking the palette. */
enum class CategoryAccent(val color: Color) {
    FOOD(Color(red = 0.95f, green = 0.69f, blue = 0.38f)),
    TRANSPORT(Color(red = 0.42f, green = 0.76f, blue = 0.82f)),
    GROCERIES(Color(red = 0.58f, green = 0.78f, blue = 0.48f)),
    BILLS(Color(red = 0.73f, green = 0.64f, blue = 0.94f)),
    SHOPPING(Color(red = 0.96f, green = 0.51f, blue = 0.66f)),
    ENTERTAINMENT(Color(red = 0.87f, green = 0.57f, blue = 0.94f)),
    HEALTH(Color(red = 0.42f, green = 0.81f, blue = 0.67f)),
    OTHER(Color(red = 0.75f, green = 0.75f, blue = 0.72f)),

    /** Falls back to `subtleInk`, matching the iOS `.neutral` case. */
    NEUTRAL(Color(red = 0.61f, green = 0.60f, blue = 0.64f));

    companion object {
        /**
         * Maps a stored category name to its accent.
         *
         * Trimmed and lowercased exactly as on iOS: the name is user data that
         * round-trips through the backend, so " Food " and "food" must resolve
         * to the same accent or the same category would change colour after a
         * sync.
         */
        fun forCategory(categoryName: String): CategoryAccent =
            when (categoryName.trim().lowercase()) {
                "food" -> FOOD
                "transport" -> TRANSPORT
                "groceries" -> GROCERIES
                "bills" -> BILLS
                "shopping" -> SHOPPING
                "entertainment" -> ENTERTAINMENT
                "health" -> HEALTH
                "other" -> OTHER
                else -> NEUTRAL
            }
    }
}

/** The palette, as literal values shared with `Theme.swift`. */
object TabbyPalette {
    val ink = Color(red = 0.94f, green = 0.93f, blue = 0.89f)
    val paper = Color(red = 0.035f, green = 0.039f, blue = 0.055f)
    val surface = Color(red = 0.075f, green = 0.080f, blue = 0.105f)
    val elevatedSurface = Color(red = 0.105f, green = 0.110f, blue = 0.140f)
    val subtleInk = Color(red = 0.61f, green = 0.60f, blue = 0.64f)
    val hairline = Color.White.copy(alpha = 0.11f)

    val accent = Color(red = 0.90f, green = 0.66f, blue = 0.22f)
    val accentBright = Color(red = 1.0f, green = 0.84f, blue = 0.43f)
    val accentDim = Color(red = 0.48f, green = 0.32f, blue = 0.10f)
    val accentGlow = accent.copy(alpha = 0.22f)

    /**
     * The iOS `.red.opacity(0.82)` used for a negative net, pre-composited over
     * [paper].
     *
     * Composited rather than left translucent because it is also handed to
     * Material's `error` role and to Glance, neither of which reliably blends an
     * alpha colour against the surface beneath it; a flat value renders the same
     * everywhere.
     */
    val negative = Color(red = 0.83f, green = 0.17f, blue = 0.18f)

    /** Seven chart ring colours, in the iOS order. */
    val ringColors = listOf(
        accentBright,
        Color(red = 0.97f, green = 0.50f, blue = 0.20f),
        Color(red = 0.67f, green = 0.45f, blue = 0.96f),
        Color(red = 0.25f, green = 0.70f, blue = 0.72f),
        Color(red = 0.96f, green = 0.34f, blue = 0.46f),
        Color(red = 0.56f, green = 0.73f, blue = 0.35f),
        Color(red = 0.77f, green = 0.76f, blue = 0.72f)
    )

    val colors = TabbyColors(
        ink = ink,
        paper = paper,
        surface = surface,
        elevatedSurface = elevatedSurface,
        subtleInk = subtleInk,
        hairline = hairline,
        accent = accent,
        accentBright = accentBright,
        accentDim = accentDim,
        accentGlow = accentGlow,
        negative = negative,
        ringColors = ringColors
    )
}

/** 24dp cards / 16dp controls, matching the iOS `cardShape` / `controlShape`. */
object TabbyShapes {
    val card = RoundedCornerShape(24.dp)
    val control = RoundedCornerShape(16.dp)
}

/**
 * Non-null by default so a composable previewed outside [TabbyTheme] renders in
 * the real palette instead of throwing — a missing provider is a wiring bug, not
 * something a preview should crash on.
 */
val LocalTabbyColors: ProvidableCompositionLocal<TabbyColors> =
    staticCompositionLocalOf { TabbyPalette.colors }

/** Shorthand so screens read `Tabby.colors.accent`, close to iOS's `Theme.accent`. */
object Tabby {
    val colors: TabbyColors
        @Composable get() = LocalTabbyColors.current
}

private val TabbyDarkColorScheme = darkColorScheme(
    primary = TabbyPalette.accent,
    onPrimary = TabbyPalette.paper,
    secondary = TabbyPalette.accentBright,
    onSecondary = TabbyPalette.paper,
    background = TabbyPalette.paper,
    onBackground = TabbyPalette.ink,
    surface = TabbyPalette.surface,
    onSurface = TabbyPalette.ink,
    surfaceVariant = TabbyPalette.elevatedSurface,
    onSurfaceVariant = TabbyPalette.subtleInk,
    outline = TabbyPalette.subtleInk,
    outlineVariant = TabbyPalette.hairline,
    error = TabbyPalette.negative
)

/**
 * Always dark. The iOS app pins `.preferredColorScheme(.dark)` on every screen,
 * and the palette has no light variant — honouring the system setting here would
 * put near-black ink on near-black paper.
 */
@Composable
fun TabbyTheme(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTabbyColors provides TabbyPalette.colors
    ) {
        MaterialTheme(
            colorScheme = TabbyDarkColorScheme,
            typography = TabbyTypography,
            shapes = Shapes(
                large = TabbyShapes.card,
                medium = TabbyShapes.control,
                small = TabbyShapes.control
            ),
            content = content
        )
    }
}
