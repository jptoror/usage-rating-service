package com.revenium.usage.invoicing.infrastructure

import com.revenium.usage.invoicing.domain.Invoice
import com.revenium.usage.invoicing.domain.InvoiceStatus
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.TenantId
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BillingPeriodStatusLookupTest {

    private val invoices = mockk<InvoiceJpaRepository>()
    private val lookup = JpaBillingPeriodStatusLookup(invoices)

    private val tenant = TenantId("tenant-a")
    private val customer = CustomerId("customer-42")
    private val august = BillingPeriod.parse("2026-08")

    private fun invoice(status: InvoiceStatus) = Invoice(
        tenantId = "tenant-a",
        customerId = "customer-42",
        periodStart = LocalDate.parse("2026-08-01"),
        periodEnd = LocalDate.parse("2026-09-01"),
        status = status,
        currency = "USD",
    )

    @Test
    fun `a closed invoice means the period is closed`() {
        every { invoices.findByTenantIdAndCustomerIdAndPeriodStart(any(), any(), any()) } returns
            invoice(InvoiceStatus.CLOSED)

        assertTrue(lookup.isClosed(tenant, customer, august))
    }

    @Test
    fun `an open invoice means the period is open`() {
        every { invoices.findByTenantIdAndCustomerIdAndPeriodStart(any(), any(), any()) } returns
            invoice(InvoiceStatus.OPEN)

        assertFalse(lookup.isClosed(tenant, customer, august))
    }

    @Test
    fun `a period with no invoice at all is open`() {
        // The case that matters most: treating "no row" as closed would misclassify
        // every first event of every month as a late adjustment.
        every { invoices.findByTenantIdAndCustomerIdAndPeriodStart(any(), any(), any()) } returns null

        assertFalse(lookup.isClosed(tenant, customer, august))
    }
}
