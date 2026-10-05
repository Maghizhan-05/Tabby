package com.maghizhan.tabby.data.sync

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Enforces the backend's money contract *before* a value is persisted locally or
 * uploaded, so an out-of-range amount fails here with a clear message instead of
 * as an opaque Postgres error mid-sync (or, worse, is silently rounded).
 *
 * The backend columns are `numeric(12,2)`: at most 12 significant digits with
 * exactly 2 after the point, so the largest representable value is
 * 9,999,999,999.99.
 */
object MoneyValidation {

    const val SCALE = 2
    const val PRECISION = 12
    val MAX_VALUE: BigDecimal = BigDecimal("9999999999.99")

    class InvalidAmountException(message: String) : IllegalArgumentException(message)

    /**
     * Returns [value] rescaled to exactly 2 decimals, or throws when it cannot be
     * represented without losing money.
     *
     * Rescaling uses [RoundingMode.UNNECESSARY]: a value with more than 2
     * decimals is a programming error, not something to silently round, because
     * rounding a user's amount is itself data loss.
     */
    fun normalizedAmount(value: BigDecimal, fieldName: String = "amount"): BigDecimal {
        val rescaled = try {
            value.setScale(SCALE, RoundingMode.UNNECESSARY)
        } catch (_: ArithmeticException) {
            throw InvalidAmountException(
                "$fieldName has more than $SCALE decimal places: ${value.toPlainString()}"
            )
        }
        if (rescaled.abs() > MAX_VALUE) {
            throw InvalidAmountException(
                "$fieldName exceeds numeric($PRECISION,$SCALE): ${value.toPlainString()}"
            )
        }
        return rescaled
    }

    /**
     * Friend balances are "how much is owed", which cannot be negative — the
     * direction is carried by which of the two columns holds the value, so a
     * negative here would double-count against `netBalance`.
     */
    fun normalizedBalance(value: BigDecimal, fieldName: String): BigDecimal {
        if (value.signum() < 0) {
            throw InvalidAmountException(
                "$fieldName must not be negative: ${value.toPlainString()}"
            )
        }
        return normalizedAmount(value, fieldName)
    }
}
