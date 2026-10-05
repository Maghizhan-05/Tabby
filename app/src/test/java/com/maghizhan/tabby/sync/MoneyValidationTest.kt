package com.maghizhan.tabby.sync

import com.maghizhan.tabby.data.sync.MoneyValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/**
 * The backend columns are `numeric(12,2)`. Validating here means an impossible
 * amount fails with a clear message before it is stored or uploaded, rather than
 * as an opaque Postgres error mid-sync or a silent rounding of someone's money.
 */
class MoneyValidationTest {

    @Test
    fun `a two decimal amount is accepted and kept exact`() {
        assertEquals(BigDecimal("12.34"), MoneyValidation.normalizedAmount(BigDecimal("12.34")))
    }

    @Test
    fun `fewer decimals are padded to the column scale`() {
        assertEquals(BigDecimal("12.00"), MoneyValidation.normalizedAmount(BigDecimal("12")))
    }

    @Test
    fun `more decimals than the column holds are rejected, never rounded`() {
        // Rounding here would silently alter a user's amount, so this must throw.
        try {
            MoneyValidation.normalizedAmount(BigDecimal("12.345"))
            fail("expected InvalidAmountException")
        } catch (e: MoneyValidation.InvalidAmountException) {
            assertTrue(e.message!!.contains("decimal places"))
        }
    }

    @Test
    fun `the largest representable value is accepted`() {
        assertEquals(
            BigDecimal("9999999999.99"),
            MoneyValidation.normalizedAmount(BigDecimal("9999999999.99"))
        )
    }

    @Test
    fun `a value beyond numeric 12 2 is rejected`() {
        try {
            MoneyValidation.normalizedAmount(BigDecimal("10000000000.00"))
            fail("expected InvalidAmountException")
        } catch (e: MoneyValidation.InvalidAmountException) {
            assertTrue(e.message!!.contains("exceeds"))
        }
    }

    @Test
    fun `a large negative amount is also range checked`() {
        try {
            MoneyValidation.normalizedAmount(BigDecimal("-10000000000.00"))
            fail("expected InvalidAmountException")
        } catch (_: MoneyValidation.InvalidAmountException) {
        }
    }

    @Test
    fun `a negative friend balance is rejected`() {
        // Direction lives in WHICH column holds the value, so a negative balance
        // would double-count against netBalance.
        try {
            MoneyValidation.normalizedBalance(BigDecimal("-1.00"), "they_owe_us")
            fail("expected InvalidAmountException")
        } catch (e: MoneyValidation.InvalidAmountException) {
            assertTrue(e.message!!.contains("they_owe_us"))
        }
    }

    @Test
    fun `a zero balance is valid`() {
        assertEquals(BigDecimal("0.00"), MoneyValidation.normalizedBalance(BigDecimal.ZERO, "we_owe_them"))
    }
}
