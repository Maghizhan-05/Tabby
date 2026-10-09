package com.maghizhan.tabby.ui.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * Amount strings for the app and the widget — the Android port of
 * `WidgetCurrencyFormatter.swift`.
 *
 * ## No currency symbol, anywhere
 *
 * Every amount is rendered as a bare number. Previously [full] asked
 * `NumberFormat.getCurrencyInstance` for the DEVICE LOCALE's symbol while
 * [compact] hard-coded a rupee sign, so the same balance read as "$1,240" on
 * Home and "₹1,240" on Friends — two currencies for one number, decided by
 * which helper a screen happened to call.
 *
 * Dropping the symbol rather than forcing one is the honest fix: an expense row
 * stores an amount and no currency code, so any symbol this layer prints is a
 * claim the data cannot support, and a wrong symbol is worse than none. It also
 * removes the whole class of defect — no future screen can reintroduce a
 * mismatch by picking the other helper.
 *
 * Grouping still follows the device locale, so thousands separators look native
 * without implying a currency.
 *
 * Two formats, matching iOS:
 *
 * - [full] is the plain grouped number, used wherever there is room (amount
 *   headlines, row amounts).
 * - [compact] abbreviates above 10,000 into K/M so a widget ring label cannot
 *   overflow its circle. The thresholds, the 999.9M+ ceiling and the
 *   one-decimal rounding are the iOS values; a different ceiling here would
 *   make the two platforms' widgets disagree on the same data.
 *
 * `BigDecimal` in, so no amount is routed through a binary float before being
 * displayed.
 */
object CurrencyFormat {

    private val MILLION_THRESHOLD = BigDecimal("999950")
    private val COMPACT_THRESHOLD = BigDecimal("10000")
    private val MAXIMUM_DISPLAYED_MILLIONS = BigDecimal("999.9")
    private val THOUSAND = BigDecimal("1000")
    private val MILLION = BigDecimal("1000000")

    fun full(amount: BigDecimal, locale: Locale = Locale.getDefault()): String =
        grouped(amount, locale)

    fun compact(amount: BigDecimal, locale: Locale = Locale.getDefault()): String {
        val sign = if (amount.signum() < 0) "-" else ""
        val magnitude = amount.abs()

        if (magnitude < COMPACT_THRESHOLD) {
            return "$sign${grouped(magnitude, locale)}"
        }

        if (magnitude < MILLION_THRESHOLD) {
            return "$sign${abbreviated(magnitude.divide(THOUSAND, 4, RoundingMode.HALF_UP))}K"
        }

        val millions = magnitude.divide(MILLION, 4, RoundingMode.HALF_UP)
        if (millions > MAXIMUM_DISPLAYED_MILLIONS) return "${sign}999.9M+"
        return "$sign${abbreviated(millions)}M"
    }

    /**
     * A net balance with an explicit sign.
     *
     * A leading "+" is added for a positive net because the two directions mean
     * opposite things on the Friends screen ("owed to you" versus "you owe"),
     * and colour alone cannot carry that for a colour-blind user.
     */
    fun signed(amount: BigDecimal, locale: Locale = Locale.getDefault()): String = when {
        amount.signum() > 0 -> "+${compact(amount, locale)}"
        amount.signum() < 0 -> "-${compact(amount.abs(), locale)}"
        else -> compact(BigDecimal.ZERO, locale)
    }

    /**
     * The locale's grouped decimal format, with no currency symbol.
     *
     * Grouping is taken from the passed locale rather than pinned to one
     * region, so separators match the rest of the device's number formatting.
     */
    private fun grouped(amount: BigDecimal, locale: Locale): String {
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
            isGroupingUsed = true
        }
        return formatter.format(amount)
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
