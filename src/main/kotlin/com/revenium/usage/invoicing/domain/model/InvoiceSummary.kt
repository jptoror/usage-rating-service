package com.revenium.usage.invoicing.domain.model

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import java.util.Currency

/**
 * A charge total for one transaction code within one origin period. Grouped by
 * `(transactionCode, originPeriod)` so an adjustment carried in from an earlier period never
 * merges into this period's consumption.
 */
data class SummaryLine(
    val transactionCode: TransactionCode,
    val transactionCount: Long,
    val totalQuantity: Quantity,
    val amount: Money,
    val originPeriod: BillingPeriod,
    val isAdjustment: Boolean,
)

/**
 * What a customer owes for a period, and why. Built by summing already-rounded per-transaction
 * amounts, never by recalculating from quantities and prices, which would round differently
 * and give a total that does not match its own lines.
 *
 * Consumption ([currentPeriodAmount]) and charges carried in from closed periods
 * ([adjustmentAmount]) are reported separately: the late-arrival policy trades "the period
 * total equals the period's consumption" for "a closed invoice never changes", and showing
 * both figures keeps that trade honest rather than hidden.
 */
data class InvoiceSummary(
    val customerId: CustomerId,
    val period: BillingPeriod,
    val currency: Currency,
    val lines: List<SummaryLine>,
    val currentPeriodAmount: Money,
    val adjustmentAmount: Money,
    val totalAmount: Money,
    val transactionCount: Long,
    val status: InvoiceStatus,
) {
    companion object {

        /** Aggregates lines into a summary; no step rounds a second time, so it closes exactly. */
        fun from(
            customerId: CustomerId,
            period: BillingPeriod,
            currency: Currency,
            lines: List<SummaryLine>,
            status: InvoiceStatus,
        ): InvoiceSummary {
            val current = Money.sum(lines.filterNot { it.isAdjustment }.map { it.amount }, currency)
            val adjustments = Money.sum(lines.filter { it.isAdjustment }.map { it.amount }, currency)

            return InvoiceSummary(
                customerId = customerId,
                period = period,
                currency = currency,
                // Deterministic: consumption first, then adjustments oldest-first.
                lines = lines.sortedWith(
                    compareBy({ it.isAdjustment }, { it.originPeriod }, { it.transactionCode.value })
                ),
                currentPeriodAmount = current,
                adjustmentAmount = adjustments,
                totalAmount = current + adjustments,
                transactionCount = lines.sumOf { it.transactionCount },
                status = status,
            )
        }
    }
}
