package com.maghizhan.tabby.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.maghizhan.tabby.R

/**
 * Tabby's type system — the Android stand-in for iOS's SF Pro / SF Rounded pair.
 *
 * iOS ships no custom face: it uses the system font, with `design: .rounded`
 * reserved for the big money numerals (`AnalyticsComponents.swift:16`). Android
 * cannot ship SF Pro, so the two closest open faces are bundled instead:
 *
 *  - **Inter** for body and UI chrome. It is the face SF Pro's proportions were
 *    most directly answered by: same humanist-grotesque skeleton, same tall
 *    x-height, so labels occupy nearly identical space and no screen needed
 *    re-layout to adopt it.
 *  - **Nunito** for amounts and analytics numerals, standing in for SF Rounded.
 *    Rounded terminals on digits are the single strongest "premium iOS" cue in
 *    the reel, and it is the one place the shape difference is visible enough to
 *    be worth a second family.
 *
 * Both are the **variable** originals from Google Fonts rather than a set of
 * static instances. One 876KB/277KB file each covers every weight, where six
 * static cuts would cost more and still quantise weight; variable axes also let
 * [FontVariation] hit a true SemiBold instead of letting the rasteriser fake one
 * by smearing the Regular. Licences live in `/licenses` (OFL requires they
 * travel with the font; they cannot sit in `res/font/`, which aapt2 compiles as
 * font resources and would fail the build on a `.txt`).
 */
/*
 * `Font(variationSettings = …)` is still marked experimental in this Compose
 * BOM (2024.12.01). Opted in deliberately and locally: it is the only way to
 * drive a variable font's weight axis, and the alternative — six static cuts —
 * costs more APK and still cannot hit a true SemiBold. Confined to these two
 * factory functions so the opt-in does not leak into screen code.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun interFont(weight: FontWeight) = Font(
    R.font.inter_variable,
    weight = weight,
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        // Inter's optical-size axis: 14 is the text-optimised end (slightly
        // looser spacing, taller lowercase). Pinned rather than left to default
        // so UI text is never rendered with display-optimised metrics.
        FontVariation.Setting("opsz", 14f)
    )
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun nunitoFont(weight: FontWeight) = Font(
    R.font.nunito_variable,
    weight = weight,
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

/** Inter, body and UI chrome. */
val InterFamily = FontFamily(
    interFont(FontWeight.Normal),
    interFont(FontWeight.Medium),
    interFont(FontWeight.SemiBold),
    interFont(FontWeight.Bold)
)

/**
 * Nunito, the SF Rounded stand-in.
 *
 * Only the weights the amounts actually use: numerals are never set lighter
 * than Medium anywhere in the app, and a family that declares weights it never
 * renders just costs resolution work on every text node.
 */
val NunitoFamily = FontFamily(
    nunitoFont(FontWeight.Medium),
    nunitoFont(FontWeight.SemiBold),
    nunitoFont(FontWeight.Bold)
)

/**
 * Trimmed line-height behaviour for the display/headline slots.
 *
 * Compose centres a text node's extra leading above and below the glyphs, which
 * leaves a big amount sitting visually low inside its own box and breaks optical
 * alignment against an adjacent ring or caption. Trimming the first line's top
 * and last line's bottom makes the box hug the glyphs, which is what SwiftUI
 * does by default — without this the Android numerals read as mis-centred even
 * at identical font sizes.
 */
private val TrimmedNumerals = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both
)

/**
 * The Material 3 slots, remapped onto Tabby's two families.
 *
 * Display and headline carry Nunito because every screen's hero element is a
 * money amount; everything from title down is Inter.
 *
 * What wiring this object does and does not do, precisely: `MaterialTheme`
 * provides `bodyLarge` as the ambient `LocalTextStyle`, so a plain `Text` that
 * sets only `fontSize`/`fontWeight`/`color` inherits Inter from [bodyLarge] and
 * needs no edit. It does NOT reach the other slots — nothing applies
 * [displayLarge] to a `Text` unless that call site asks for it. So the slots
 * below whose BEHAVIOUR matters (the rounded face and the line-height trim on
 * hero numerals) are requested explicitly at the sites that need them, via
 * [moneyStyle] for amounts or `MaterialTheme.typography.x` for structure.
 */
val TabbyTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        // Large numerals set at default tracking look gappy; iOS tightens
        // optically as size grows, which this approximates.
        letterSpacing = (-0.8).sp,
        lineHeightStyle = TrimmedNumerals
    ),
    displayMedium = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.6).sp,
        lineHeightStyle = TrimmedNumerals
    ),
    displaySmall = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp,
        lineHeightStyle = TrimmedNumerals
    ),
    headlineLarge = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
        lineHeightStyle = TrimmedNumerals
    ),
    headlineMedium = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        lineHeightStyle = TrimmedNumerals
    ),
    headlineSmall = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        lineHeightStyle = TrimmedNumerals
    ),
    titleLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        // The reel's section captions ("TODAY", "RECENT ACTIVITY") are tracked
        // out; iOS gets this from its caption styles, Android needs it stated.
        letterSpacing = 0.6.sp
    ),
    labelSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)

/** OpenType tabular figures; the Compose spelling of iOS's `monospacedDigit()`. */
const val TABULAR_FIGURES = "tnum"

/**
 * The style every money amount must use.
 *
 * One helper rather than per-site styling because an amount needs THREE things
 * to look iOS-correct and they were previously applied unevenly: the rounded
 * face (Nunito, standing in for SF Rounded), tabular figures so a column of
 * amounts aligns and a ticking total does not jitter, and inheritance of
 * whatever size/colour/weight the call site already set. Call sites keep their
 * own `fontSize`/`fontWeight`; this overrides only family and figures, so a
 * single edit here re-faces every amount in the app.
 */
@Composable
fun moneyStyle(): TextStyle = LocalTextStyle.current.copy(
    fontFamily = NunitoFamily,
    fontFeatureSettings = TABULAR_FIGURES,
    // The trim the display/headline slots declare, applied here too: amounts
    // are set by explicit fontSize at their call sites rather than by taking a
    // slot wholesale, so without this the hero numerals would keep Compose's
    // centred leading and sit visibly low in their own box — the mis-centring
    // against an adjacent ring that the slots were shaped to avoid.
    lineHeightStyle = TrimmedNumerals
)
