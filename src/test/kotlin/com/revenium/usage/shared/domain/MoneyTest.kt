package com.revenium.usage.shared.domain

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyTest {

    private val usd: Currency = Currency.getInstance("USD")
    private val eur: Currency = Currency.getInstance("EUR")

    // --- rounding ----------------------------------------------------------

    @Test
    fun `rounds HALF_UP at the canonical scale`() {
        assertEquals("0.0001", Money.of(BigDecimal("0.00005"), usd).amount.toPlainString())
        assertEquals("0.0002", Money.of(BigDecimal("0.00015"), usd).amount.toPlainString())
    }

    @Test
    fun `rounds half away from zero, not to even`() {
        // HALF_UP, not HALF_EVEN: 0.00005 must become 0.0001, never 0.0000.
        // Banker's rounding would under-bill systematically on ties.
        assertEquals("0.0001", Money.of(BigDecimal("0.00005"), usd).amount.toPlainString())
        assertEquals("0.0003", Money.of(BigDecimal("0.00025"), usd).amount.toPlainString())
    }

    @Test
    fun `always carries the canonical scale`() {
        assertEquals(Money.SCALE, Money.of(BigDecimal("5"), usd).amount.scale())
        assertEquals(Money.SCALE, Money.zero(usd).amount.scale())
    }

    @Test
    fun `rejects an amount with more precision than the column can hold`() {
        // Constructed directly rather than through of(): the factory rounds, but a
        // caller must not be able to smuggle in unrepresentable precision.
        assertFailsWith<IllegalArgumentException> { Money(BigDecimal("1.00001"), usd) }
    }

    // --- rating ------------------------------------------------------------

    @Test
    fun `rate multiplies exactly and rounds once`() {
        val amount = Money.rate(Quantity.of(3), UnitPrice(BigDecimal("0.003333")), usd)
        // 3 x 0.003333 = 0.009999 exactly; rounding once at scale 4 gives 0.0100.
        assertEquals("0.0100", amount.amount.toPlainString())
    }

    @Test
    fun `rate does not round the unit price first`() {
        // Rounding the unit price to 4 dp would give 0.0033 x 1000 = 3.3000.
        // Multiplying first and rounding once gives 3.3330 -- a 0.003 difference on
        // a single line, which compounds across a real invoice.
        val amount = Money.rate(Quantity.of(1000), UnitPrice(BigDecimal("0.003333")), usd)
        assertEquals("3.3330", amount.amount.toPlainString())
    }

    @Test
    fun `rate handles a fractional quantity`() {
        val amount = Money.rate(Quantity.of(BigDecimal("2.5")), UnitPrice(BigDecimal("2.000000")), usd)
        assertEquals("5.0000", amount.amount.toPlainString())
    }

    @Test
    fun `rate of a zero unit price is zero, not an error`() {
        // A legitimately free transaction code still produces a traceable rated row.
        val amount = Money.rate(Quantity.of(10), UnitPrice(BigDecimal.ZERO), usd)
        assertTrue(amount.isZero())
    }

    @Test
    fun `rate handles a large amount without overflowing the column`() {
        // NUMERIC(19,4) holds 15 integer digits; this must survive intact.
        val amount = Money.rate(Quantity.of(1_000_000), UnitPrice(BigDecimal("999999.999999")), usd)
        assertEquals("999999999999.0000", amount.amount.toPlainString())
        assertTrue(amount.amount.precision() <= 19)
    }

    // --- arithmetic --------------------------------------------------------

    @Test
    fun `sum adds already-rounded amounts without rounding again`() {
        // The invariant that makes an invoice reconcile: the total is the sum of the
        // line amounts, each rounded when it was calculated. Rounding the total
        // separately is how a total stops matching its lines.
        val lines = listOf(
            Money.of(BigDecimal("0.3333"), usd),
            Money.of(BigDecimal("0.3333"), usd),
            Money.of(BigDecimal("0.3333"), usd),
        )
        assertEquals("0.9999", Money.sum(lines, usd).amount.toPlainString())
    }

    @Test
    fun `sum of nothing is zero in the requested currency`() {
        val total = Money.sum(emptyList(), eur)
        assertTrue(total.isZero())
        assertEquals(eur, total.currency)
    }

    @Test
    fun `plus and minus keep the currency`() {
        val a = Money.of(BigDecimal("10.5000"), usd)
        val b = Money.of(BigDecimal("2.2500"), usd)
        assertEquals("12.7500", (a + b).amount.toPlainString())
        assertEquals("8.2500", (a - b).amount.toPlainString())
    }

    @Test
    fun `refuses to combine different currencies`() {
        // Silently adding USD to EUR would produce a meaningless number that looks
        // entirely plausible on an invoice.
        assertFailsWith<IllegalArgumentException> {
            Money.of(BigDecimal("1"), usd) + Money.of(BigDecimal("1"), eur)
        }
    }

    // --- comparison --------------------------------------------------------

    @Test
    fun `compares by value regardless of scale`() {
        // BigDecimal.equals says 2.0 != 2.00. compareTo is the comparison billing
        // code actually means, and this is the classic source of phantom mismatches.
        assertEquals(0, Money(BigDecimal("2.0"), usd).compareTo(Money(BigDecimal("2.00"), usd)))
        assertTrue(Money.of(BigDecimal("3"), usd) > Money.of(BigDecimal("2"), usd))
    }

    @Test
    fun `detects zero regardless of scale`() {
        assertTrue(Money(BigDecimal("0.00"), usd).isZero())
        assertTrue(Money.zero(usd).isZero())
        assertFalse(Money.of(BigDecimal("0.0001"), usd).isZero())
    }

    @Test
    fun `detects a negative amount`() {
        assertTrue(Money.of(BigDecimal("-1"), usd).isNegative())
        assertFalse(Money.zero(usd).isNegative())
    }
}
