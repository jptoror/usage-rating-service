package com.revenium.usage.invoicing.infrastructure.persistence

import com.revenium.usage.invoicing.domain.model.Invoice
import com.revenium.usage.invoicing.domain.model.InvoiceLine
import com.revenium.usage.invoicing.domain.model.InvoiceStatus
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import kotlin.test.assertEquals

class InvoiceEntityTest {

    private val usd: Currency = Currency.getInstance("USD")
    private val tenant = TenantId("tenant-a")
    private val customer = CustomerId("customer-42")
    private val september = BillingPeriod.parse("2026-09")
    private val now = Instant.parse("2026-10-01T00:00:00Z")

    private fun open() = Invoice(
        tenantId = tenant,
        customerId = customer,
        period = september,
        currency = usd,
        createdAt = Instant.parse("2026-09-01T00:00:00Z"),
        id = 1L,
    )

    @Test
    fun `an open invoice round trips`() {
        val original = open()

        assertEquals(original, InvoiceEntity.fromDomain(original).toDomain())
    }

    @Test
    fun `a closed invoice round trips with its frozen totals`() {
        val closed = open().close(
            currentPeriod = Money.of(BigDecimal("100.0000"), usd),
            adjustments = Money.of(BigDecimal("25.0000"), usd),
            transactions = 7,
            now = now,
        )

        val mapped = InvoiceEntity.fromDomain(closed).toDomain()

        assertEquals(closed, mapped)
        assertEquals(InvoiceStatus.CLOSED, mapped.status)
        assertEquals("125.0000", mapped.totalAmount.amount.toPlainString())
        assertEquals(now, mapped.closedAt)
    }

    @Test
    fun `the period maps to the half-open day range the table stores`() {
        // period_end is the first day of the NEXT period, exclusive -- the same
        // half-open convention pricing rule validity uses.
        val entity = InvoiceEntity.fromDomain(open())

        assertEquals(LocalDate.parse("2026-09-01"), entity.periodStart)
        assertEquals(LocalDate.parse("2026-10-01"), entity.periodEnd)
    }

    @Test
    fun `a line round trips, taking its currency from the invoice`() {
        // The line has no currency column: it belongs to exactly one invoice and cannot
        // be denominated differently from its header.
        val line = InvoiceLine(
            tenantId = tenant,
            invoiceId = 1L,
            transactionCode = TransactionCode("VEHICLE_REGISTRATION"),
            transactionCount = 7,
            totalQuantity = Quantity(BigDecimal("14")),
            amount = Money.of(BigDecimal("125.0000"), usd),
            originPeriod = september,
            id = 2L,
        )

        assertEquals(line, InvoiceLineEntity.fromDomain(line).toDomain(usd))
    }
}
