package com.revenium.usage.invoicing.infrastructure.persistence

import com.revenium.usage.invoicing.domain.model.InvoiceLine
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * Persistence mapping for one line of a closed invoice.
 *
 * The currency is not on this table: a line belongs to exactly one invoice and takes its
 * currency from the invoice header, so [toDomain] is handed it rather than reading a
 * column that could disagree with the header.
 */
@Entity
@Table(name = "invoice_line")
class InvoiceLineEntity(

    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,

    @Column(name = "invoice_id", nullable = false, updatable = false)
    val invoiceId: Long,

    @Column(name = "transaction_code", nullable = false, updatable = false)
    val transactionCode: String,

    @Column(name = "transaction_count", nullable = false, updatable = false)
    val transactionCount: Long,

    @Column(name = "total_quantity", nullable = false, updatable = false, precision = 19, scale = 6)
    val totalQuantity: BigDecimal,

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    val amount: BigDecimal,

    /** The period the usage happened in, which may predate this invoice. */
    @Column(name = "origin_period", nullable = false, updatable = false)
    val originPeriod: LocalDate,

    @Column(name = "is_adjustment", nullable = false, updatable = false)
    val isAdjustment: Boolean = false,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
) {

    fun toDomain(currency: Currency) = InvoiceLine(
        tenantId = TenantId(tenantId),
        invoiceId = invoiceId,
        transactionCode = TransactionCode(transactionCode),
        transactionCount = transactionCount,
        totalQuantity = Quantity(totalQuantity),
        amount = Money(amount, currency),
        originPeriod = BillingPeriod.of(originPeriod),
        isAdjustment = isAdjustment,
        id = id,
    )

    companion object {
        fun fromDomain(line: InvoiceLine) = InvoiceLineEntity(
            tenantId = line.tenantId.value,
            invoiceId = line.invoiceId,
            transactionCode = line.transactionCode.value,
            transactionCount = line.transactionCount,
            totalQuantity = line.totalQuantity.value,
            amount = line.amount.amount,
            originPeriod = line.originPeriod.startDate,
            isAdjustment = line.isAdjustment,
            id = line.id,
        )
    }
}
