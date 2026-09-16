package com.revenium.usage.invoicing.domain

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

/**
 * Whether a billing period still accepts charges.
 *
 * Persisted by name, not ordinal: an ordinal silently reinterprets every existing row
 * the moment a constant is inserted in the middle, which on this table would rewrite
 * which invoices are considered settled.
 */
enum class InvoiceStatus { OPEN, CLOSED }

/**
 * A customer's charges for one billing period.
 *
 * While the period is `OPEN` the summary is computed on read from `rated_transaction`,
 * so it always reflects the latest rating with no recomputation step and no cache to
 * invalidate. Closing materialises the totals here and into `invoice_line`.
 *
 * **A closed invoice is never modified again.** That is the whole of the late-arrival
 * policy: an event that turns up after its period closed is charged as an adjustment in
 * the open period, carrying its original period for traceability, rather than reopening
 * a figure the customer has already seen.
 *
 * [totalAmount] is stored rather than derived so a database CHECK can enforce
 * `total = currentPeriod + adjustment`. An aggregation bug then fails at write time
 * instead of surfacing in an audit months later.
 */
@Entity
@Table(name = "invoice")
class Invoice(

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

    /** Usage that belongs to this period. */
    @Column(name = "current_period_amount", nullable = false, precision = 19, scale = 4)
    var currentPeriodAmount: BigDecimal = BigDecimal.ZERO,

    /** Late arrivals from earlier, closed periods, charged here. */
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

    val isClosed: Boolean get() = status == InvoiceStatus.CLOSED

    /**
     * Freezes the totals and closes the period.
     *
     * Refuses to close twice: a second close would overwrite figures a customer may
     * already have been billed from, which is exactly what the immutability rule exists
     * to prevent.
     */
    fun close(
        currentPeriod: BigDecimal,
        adjustments: BigDecimal,
        transactions: Long,
        now: Instant,
    ) {
        check(!isClosed) { "Invoice $id for period $periodStart is already closed" }

        currentPeriodAmount = currentPeriod
        adjustmentAmount = adjustments
        // Never rounded again here: both operands are already at the canonical scale,
        // each having been rounded when its own transaction was rated.
        totalAmount = currentPeriod.add(adjustments)
        transactionCount = transactions
        status = InvoiceStatus.CLOSED
        closedAt = now
    }
}
