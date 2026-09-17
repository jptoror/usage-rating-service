package com.revenium.usage.invoicing.infrastructure.persistence

import com.revenium.usage.invoicing.domain.model.Invoice
import com.revenium.usage.invoicing.domain.model.InvoiceStatus
import com.revenium.usage.invoicing.domain.port.out.InvoiceStore
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.TenantId
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Currency
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BillingPeriodStatusLookupTest {

    private val invoices = mockk<InvoiceStore>()
    private val lookup = JpaBillingPeriodStatusLookup(invoices)

    private val tenant = TenantId("tenant-a")
    private val customer = CustomerId("customer-42")
    private val august = BillingPeriod.parse("2026-08")

    private fun invoice(status: InvoiceStatus) = Invoice(
        tenantId = tenant,
        customerId = customer,
        period = august,
        currency = Currency.getInstance("USD"),
        status = status,
        closedAt = Instant.parse("2026-09-01T00:00:00Z").takeIf { status == InvoiceStatus.CLOSED },
    )

    @Test
    fun `a closed invoice means the period is closed`() {
        every { invoices.findInvoice(any(), any(), any()) } returns invoice(InvoiceStatus.CLOSED)

        assertTrue(lookup.isClosed(tenant, customer, august))
    }

    @Test
    fun `an open invoice means the period is open`() {
        every { invoices.findInvoice(any(), any(), any()) } returns invoice(InvoiceStatus.OPEN)

        assertFalse(lookup.isClosed(tenant, customer, august))
    }

    @Test
    fun `a period with no invoice at all is open`() {
        // The case that matters most: treating "no row" as closed would misclassify
        // every first event of every month as a late adjustment.
        every { invoices.findInvoice(any(), any(), any()) } returns null

        assertFalse(lookup.isClosed(tenant, customer, august))
    }
}
