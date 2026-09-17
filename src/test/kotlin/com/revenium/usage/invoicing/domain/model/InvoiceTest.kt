package com.revenium.usage.invoicing.domain.model

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.tenancy.TenantId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvoiceTest {

    private val now = Instant.parse("2026-09-01T00:00:00Z")
    private val usd: Currency = Currency.getInstance("USD")

    private fun invoice() = Invoice(
        tenantId = TenantId("tenant-a"),
        customerId = CustomerId("customer-42"),
        period = BillingPeriod.parse("2026-08"),
        currency = usd,
    )

    private fun usd(amount: String) = Money.of(BigDecimal(amount), usd)

    @Test
    fun `starts open`() {
        assertFalse(invoice().isClosed)
    }

    @Test
    fun `closing freezes the totals`() {
        val closed = invoice().close(usd("1240.0000"), usd("37.5000"), 620, now)

        assertTrue(closed.isClosed)
        assertEquals("1277.5000", closed.totalAmount.amount.toPlainString())
        assertEquals(620, closed.transactionCount)
        assertEquals(now, closed.closedAt)
    }

    @Test
    fun `the total is exactly the sum of its parts`() {
        // A database CHECK enforces the same equality, so an aggregation bug fails at
        // write time rather than surfacing in an audit months later.
        val closed = invoice().close(usd("0.3333"), usd("0.3333"), 2, now)

        assertEquals("0.6666", closed.totalAmount.amount.toPlainString())
    }

    @Test
    fun `separates this period's usage from late adjustments`() {
        // Both figures are visible so a reader can tell what was consumed this month
        // from what is merely being charged this month.
        val closed = invoice().close(usd("100.0000"), usd("25.0000"), 10, now)

        assertEquals("100.0000", closed.currentPeriodAmount.amount.toPlainString())
        assertEquals("25.0000", closed.adjustmentAmount.amount.toPlainString())
    }

    @Test
    fun `refuses to close twice`() {
        // A second close would overwrite figures the customer may already have been
        // billed from -- exactly what immutability exists to prevent.
        val closed = invoice().close(usd("100.0000"), usd("0.0000"), 1, now)

        assertFailsWith<IllegalStateException> {
            closed.close(usd("999.0000"), usd("0.0000"), 1, now)
        }
        assertEquals("100.0000", closed.totalAmount.amount.toPlainString())
    }

    @Test
    fun `closes an empty period at zero rather than failing`() {
        // A customer with no usage still gets a closed period, so reconciliation can
        // account for it instead of finding a gap.
        val closed = invoice().close(Money.zero(usd), Money.zero(usd), 0, now)

        assertTrue(closed.isClosed)
        assertEquals(0, closed.totalAmount.compareTo(Money.zero(usd)))
    }
}
