package com.maghizhan.tabby.ui.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * Currency strings for the app and the widget — the Android port of
 * `WidgetCurrencyFormatter.swift`.
 *
 * Two formats, matching iOS exactly:
 *
 * - [full] is the locale's own currency format, used wherever there is room
 *   (amount headlines, row amounts).
 * - [compact] abbreviates above ₹10,000 into K/M so a widget ring label cannot
 *   overflow its circle. The thresholds, the 999.9M+ ceiling and the
 *   one-decimal rounding are the iOS values; a different ceiling here would
 *   make the two platforms' widgets disagree on the same data.
 *
 * `BigDecimal` in, so no amount is routed through a binary float before being
 * displayed.
 */
object CurrencyFormat {

    private const val RUPEE = "₹"
    private val MILLION_THRESHOLD = BigDecimal("999950")
    private val COMPACT_THRESHOLD = BigDecimal("10000")
    private val MAXIMUM_DISPLAYED_MILLIONS = BigDecimal("999.9")
    private val THOUSAND = BigDecimal("1000")
    private val MILLION = BigDecimal("1000000")

    fun full(amount: BigDecimal, locale: Locale = Locale.getDefault()): String {
        val formatter = NumberFormat.getCurrencyInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
        return formatter.format(amount)
    }

    fun compact(amount: BigDecimal, locale: Locale = Locale.getDefault()): String {
        val sign = if (amount.signum() < 0) "-" else ""
        val magnitude = amount.abs()

        if (magnitude < COMPACT_THRESHOLD) {
            val formatter = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply {
                minimumFractionDigits = 0
                maximumFractionDigits = 2
                isGroupingUsed = true
            }
            return "$sign$RUPEE${formatter.format(magnitude)}"
        }

        if (magnitude < MILLION_THRESHOLD) {
            return "$sign$RUPEE${abbreviated(magnitude.divide(THOUSAND, 4, RoundingMode.HALF_UP))}K"
        }

        val millions = magnitude.divide(MILLION, 4, RoundingMode.HALF_UP)
        if (millions > MAXIMUM_DISPLAYED_MILLIONS) return "$sign${RUPEE}999.9M+"
        return "$sign$RUPEE${abbreviated(millions)}M"
    }

    /**
     * A net balance with an explicit sign.
     *
     * A leading "+" is added for a positive net because the two directions mean
     * opposite things on the Friends screen ("owed to you" versus "you owe"),
     * and colour alone cannot carry that for a colour-blind user.
     */
    fun signed(amount: BigDecimal, locale: Locale = Locale.getDefault()): String = when {
        amount.signum() > 0 -> "+${full(amount, locale)}"
        amount.signum() < 0 -> "-${full(amount.abs(), locale)}"
        else -> full(BigDecimal.ZERO, locale)
    }

    /** One decimal, with a trailing `.0` dropped — "12K", not "12.0K". */
    private fun abbreviated(value: BigDecimal): String {
        val rounded = value.setScale(1, RoundingMode.HALF_UP)
        return if (rounded.stripTrailingZeros().scale() <= 0) {
            rounded.setScale(0, RoundingMode.HALF_UP).toPlainString()
        } else {
            rounded.toPlainString()
        }
    }
}
