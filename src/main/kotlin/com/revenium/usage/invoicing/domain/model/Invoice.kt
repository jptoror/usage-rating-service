package com.revenium.usage.invoicing.domain.model

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.tenancy.TenantId
import java.time.Instant

/**
 * Whether a billing period still accepts charges. Persisted by name, not ordinal: inserting a
 * constant in the middle would otherwise rewrite which invoices are considered settled.
 */
enum class InvoiceStatus { OPEN, CLOSED }

/**
 * A customer's charges for one billing period: computed on read while `OPEN`, materialised
 * here and into the invoice lines on close.
 *
 * A closed invoice is never modified again — an event arriving after its period closed is
 * charged as an adjustment in the open period, carrying its origin period for traceability.
 *
 * [totalAmount] is carried rather than derived so a database CHECK can enforce
 * `total = currentPeriod + adjustment`, failing an aggregation bug at write time instead of
 * in an audit months later.
 */
data class Invoice(
    val tenantId: TenantId,
    val customerId: CustomerId,
    val period: BillingPeriod,
    val currency: java.util.Currency,
    val status: InvoiceStatus = InvoiceStatus.OPEN,
    /** Usage that belongs to this period. */
    val currentPeriodAmount: Money = Money.zero(currency),
    /** Late arrivals from earlier, closed periods, charged here. */
    val adjustmentAmount: Money = Money.zero(currency),
    val totalAmount: Money = Money.zero(currency),
    val transactionCount: Long = 0,
    val closedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val id: Long = 0,
) {
    init {
        require(totalAmount.compareTo(currentPeriodAmount + adjustmentAmount) == 0) {
            "Invoice total must equal currentPeriod + adjustment"
        }
        require(transactionCount >= 0) { "transactionCount must not be negative" }
        require((status == InvoiceStatus.CLOSED) == (closedAt != null)) {
            "A closed invoice records when it closed, and an open one does not"
        }
    }

    val isClosed: Boolean get() = status == InvoiceStatus.CLOSED

    /**
     * Freezes the totals and closes the period. Refuses to close twice: a second close would
     * overwrite figures a customer may already have been billed from.
     */
    fun close(
        currentPeriod: Money,
        adjustments: Money,
        transactions: Long,
        now: Instant,
    ): Invoice {
        check(!isClosed) { "Invoice $id for period $period is already closed" }

        return copy(
            currentPeriodAmount = currentPeriod,
            adjustmentAmount = adjustments,
            // Not rounded again: both operands were rounded when their transactions were rated.
            totalAmount = currentPeriod + adjustments,
            transactionCount = transactions,
            status = InvoiceStatus.CLOSED,
            closedAt = now,
        )
    }
}
