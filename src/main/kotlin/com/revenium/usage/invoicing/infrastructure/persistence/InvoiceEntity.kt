package com.revenium.usage.invoicing.infrastructure.persistence

import com.revenium.usage.invoicing.domain.model.Invoice
import com.revenium.usage.invoicing.domain.model.InvoiceStatus
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.tenancy.TenantId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

/**
 * Persistence mapping for an invoice.
 *
 * Mirrors the table, not the domain: primitives and nullable columns, no invariants. The
 * rules live in [Invoice], which this converts to and from.
 */
@Entity
@Table(name = "invoice")
class InvoiceEntity(

    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,

    @Column(name = "customer_id", nullable = false, updatable = false)
    val customerId: String,

    /** First day of the period, inclusive. */
    @Column(name = "period_start", nullable = false, updatable = false)
    val periodStart: LocalDate,

    /** First day of the next period, exclusive — mirroring pricing rule validity. */
    @Column(name = "period_end", nullable = false, updatable = false)
    val periodEnd: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: InvoiceStatus = InvoiceStatus.OPEN,

    // columnDefinition, not just length: the column is CHAR(3) (bpchar) and
    // Hibernate would otherwise infer varchar, which ddl-auto=validate rejects.
    @Column(name = "currency", nullable = false, updatable = false, columnDefinition = "bpchar(3)")
    val currency: String,

    @Column(name = "current_period_amount", nullable = false, precision = 19, scale = 4)
    var currentPeriodAmount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "adjustment_amount", nullable = false, precision = 19, scale = 4)
    var adjustmentAmount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    var totalAmount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "transaction_count", nullable = false)
    var transactionCount: Long = 0,

    @Column(name = "closed_at")
    var closedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
) {

    fun toDomain(): Invoice {
        val currencyInstance = Currency.getInstance(currency)
        return Invoice(
            tenantId = TenantId(tenantId),
            customerId = CustomerId(customerId),
            period = BillingPeriod.of(periodStart),
            currency = currencyInstance,
            status = status,
            currentPeriodAmount = Money(currentPeriodAmount, currencyInstance),
            adjustmentAmount = Money(adjustmentAmount, currencyInstance),
            totalAmount = Money(totalAmount, currencyInstance),
            transactionCount = transactionCount,
            closedAt = closedAt,
            createdAt = createdAt,
            id = id,
        )
    }

    companion object {
        fun fromDomain(invoice: Invoice) = InvoiceEntity(
            tenantId = invoice.tenantId.value,
            customerId = invoice.customerId.value,
            periodStart = invoice.period.startDate,
            periodEnd = invoice.period.endDate,
            status = invoice.status,
            currency = invoice.currency.currencyCode,
            currentPeriodAmount = invoice.currentPeriodAmount.amount,
            adjustmentAmount = invoice.adjustmentAmount.amount,
            totalAmount = invoice.totalAmount.amount,
            transactionCount = invoice.transactionCount,
            closedAt = invoice.closedAt,
            createdAt = invoice.createdAt,
            id = invoice.id,
        )
    }
}
