package com.revenium.usage.invoicing.infrastructure

import com.revenium.usage.invoicing.domain.BillingPeriodStatusLookup
import com.revenium.usage.invoicing.domain.Invoice
import com.revenium.usage.invoicing.domain.InvoiceLine
import com.revenium.usage.invoicing.domain.InvoiceStatus
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.TenantId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface InvoiceJpaRepository : JpaRepository<Invoice, Long> {

    fun findByTenantIdAndCustomerIdAndPeriodStart(
        tenantId: String,
        customerId: String,
        periodStart: LocalDate,
    ): Invoice?

    fun findByTenantIdAndCustomerIdOrderByPeriodStartDesc(
        tenantId: String,
        customerId: String,
    ): List<Invoice>
}

@Repository
interface InvoiceLineJpaRepository : JpaRepository<InvoiceLine, Long> {

    /** Ordered so a closed invoice reads back the same way every time. */
    fun findByInvoiceIdOrderByOriginPeriodAscTransactionCodeAsc(invoiceId: Long): List<InvoiceLine>
}

/**
 * Answers whether a period is closed, for the late-arrival rule.
 *
 * A period with no invoice row is open: usage for a period nobody has closed yet is
 * ordinary, on-time usage, and treating "no row" as closed would misclassify every
 * first event of every month as a late adjustment.
 */
@Repository
class JpaBillingPeriodStatusLookup(
    private val invoices: InvoiceJpaRepository,
) : BillingPeriodStatusLookup {

    override fun isClosed(tenant: TenantId, customer: CustomerId, period: BillingPeriod): Boolean =
        invoices.findByTenantIdAndCustomerIdAndPeriodStart(
            tenant.value, customer.value, period.startDate,
        )?.status == InvoiceStatus.CLOSED
}
