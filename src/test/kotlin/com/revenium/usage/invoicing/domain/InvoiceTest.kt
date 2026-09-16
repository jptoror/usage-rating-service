package com.revenium.usage.invoicing.domain

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvoiceTest {

    private val now = Instant.parse("2026-09-01T00:00:00Z")

    private fun invoice() = Invoice(
        tenantId = "tenant-a",
        customerId = "customer-42",
        periodStart = LocalDate.parse("2026-08-01"),
        periodEnd = LocalDate.parse("2026-09-01"),
        currency = "USD",
    )

    @Test
    fun `starts open`() {
        assertFalse(invoice().isClosed)
    }

    @Test
    fun `closing freezes the totals`() {
        val invoice = invoice()

        invoice.close(BigDecimal("1240.0000"), BigDecimal("37.5000"), 620, now)

        assertTrue(invoice.isClosed)
        assertEquals("1277.5000", invoice.totalAmount.toPlainString())
        assertEquals(620, invoice.transactionCount)
        assertEquals(now, invoice.closedAt)
    }

    @Test
    fun `the total is exactly the sum of its parts`() {
        // A database CHECK enforces the same equality, so an aggregation bug fails at
        // write time rather than surfacing in an audit months later.
        val invoice = invoice()

        invoice.close(BigDecimal("0.3333"), BigDecimal("0.3333"), 2, now)

        assertEquals("0.6666", invoice.totalAmount.toPlainString())
    }

    @Test
    fun `separates this period's usage from late adjustments`() {
        // Both figures are visible so a reader can tell what was consumed this month
        // from what is merely being charged this month.
        val invoice = invoice()

        invoice.close(BigDecimal("100.0000"), BigDecimal("25.0000"), 10, now)

        assertEquals("100.0000", invoice.currentPeriodAmount.toPlainString())
        assertEquals("25.0000", invoice.adjustmentAmount.toPlainString())
    }

    @Test
    fun `refuses to close twice`() {
        // A second close would overwrite figures the customer may already have been
        // billed from -- exactly what immutability exists to prevent.
        val invoice = invoice()
        invoice.close(BigDecimal("100.0000"), BigDecimal.ZERO, 1, now)

        assertFailsWith<IllegalStateException> {
            invoice.close(BigDecimal("999.0000"), BigDecimal.ZERO, 1, now)
        }
        assertEquals("100.0000", invoice.totalAmount.toPlainString())
    }

    @Test
    fun `closes an empty period at zero rather than failing`() {
        // A customer with no usage still gets a closed period, so reconciliation can
        // account for it instead of finding a gap.
        val invoice = invoice()

        invoice.close(BigDecimal.ZERO, BigDecimal.ZERO, 0, now)

        assertTrue(invoice.isClosed)
        assertEquals(0, invoice.totalAmount.compareTo(BigDecimal.ZERO))
    }
}
