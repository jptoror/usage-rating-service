package com.revenium.usage.invoicing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One line of a closed invoice: a transaction code's total within an origin period.
 *
 * The grouping is `(transactionCode, originPeriod)` rather than transaction code alone,
 * so this period's consumption and an adjustment carried in from August appear as
 * separate lines. Merging them would produce a correct total that nobody could explain.
 *
 * [amount] is the sum of already-rounded per-transaction amounts, never a fresh
 * calculation over the totals. That is what makes each line reconcile exactly with its
 * events, and the invoice total reconcile exactly with its lines.
 */
@Entity
@Table(name = "invoice_line")
class InvoiceLine(

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
)
