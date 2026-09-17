package com.revenium.usage.invoicing.infrastructure.persistence

import com.revenium.usage.invoicing.domain.model.Invoice
import com.revenium.usage.invoicing.domain.model.InvoiceLine
import com.revenium.usage.invoicing.domain.model.InvoiceStatus
import com.revenium.usage.invoicing.domain.port.out.BillingPeriodStatusLookup
import com.revenium.usage.invoicing.domain.port.out.InvoiceStore
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.TenantId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface InvoiceJpaRepository : JpaRepository<InvoiceEntity, Long> {

    fun findByTenantIdAndCustomerIdAndPeriodStart(
        tenantId: String,
        customerId: String,
        periodStart: LocalDate,
    ): InvoiceEntity?

    fun findByTenantIdAndCustomerIdOrderByPeriodStartDesc(
        tenantId: String,
        customerId: String,
    ): List<InvoiceEntity>
}

@Repository
interface InvoiceLineJpaRepository : JpaRepository<InvoiceLineEntity, Long> {

    /** Ordered so a closed invoice reads back the same way every time. */
    fun findByInvoiceIdOrderByOriginPeriodAscTransactionCodeAsc(invoiceId: Long): List<InvoiceLineEntity>
}

/**
 * The JPA adapter for [InvoiceStore].
 *
 * Translates between the domain model and the persistence entities. Neither entity
 * escapes this package.
 */
@Repository
class JpaInvoiceStore(
    private val invoices: InvoiceJpaRepository,
    private val lines: InvoiceLineJpaRepository,
) : InvoiceStore {

    override fun findInvoice(
        tenant: TenantId,
        customer: CustomerId,
        period: BillingPeriod,
    ): Invoice? =
        invoices
            .findByTenantIdAndCustomerIdAndPeriodStart(tenant.value, customer.value, period.startDate)
            ?.toDomain()

    override fun findLines(invoice: Invoice): List<InvoiceLine> =
        lines
            .findByInvoiceIdOrderByOriginPeriodAscTransactionCodeAsc(invoice.id)
            // The currency comes from the header rather than a column of its own: a line
            // belongs to exactly one invoice and cannot be denominated differently.
            .map { it.toDomain(invoice.currency) }

    override fun saveInvoice(invoice: Invoice): Invoice =
        invoices.save(InvoiceEntity.fromDomain(invoice)).toDomain()

    override fun saveLines(lines: List<InvoiceLine>): List<InvoiceLine> {
        val saved = this.lines.saveAll(lines.map(InvoiceLineEntity::fromDomain))
        return saved.zip(lines).map { (entity, line) -> entity.toDomain(line.amount.currency) }
    }
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
    private val invoices: InvoiceStore,
) : BillingPeriodStatusLookup {

    override fun isClosed(tenant: TenantId, customer: CustomerId, period: BillingPeriod): Boolean =
        invoices.findInvoice(tenant, customer, period)?.status == InvoiceStatus.CLOSED
}
