package com.revenium.usage.invoicing.domain

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import java.math.BigDecimal
import java.util.Currency

/**
 * A charge total for one transaction code within one origin period.
 *
 * Grouped by `(transactionCode, originPeriod)` so an adjustment carried in from an
 * earlier period never merges into this period's consumption. A reader can always tell
 * what was used this month from what is merely being billed this month.
 */
data class SummaryLine(
    val transactionCode: String,
    val transactionCount: Long,
    val totalQuantity: BigDecimal,
    val amount: Money,
    val originPeriod: BillingPeriod,
    val isAdjustment: Boolean,
)

/**
 * What a customer owes for a period, and why.
 *
 * Built by summing already-rounded per-transaction amounts — never by recalculating from
 * quantities and prices. Recalculating would round differently and produce a total that
 * does not match the sum of its lines, which is precisely the discrepancy a reviewer
 * looks for.
 *
 * The three figures are reported separately on purpose:
 *
 * - [currentPeriodAmount] — what this period consumed
 * - [adjustmentAmount] — what earlier, closed periods are being charged for now
 * - [totalAmount] — what is actually owed
 *
 * The late-arrival policy trades "the period total equals the period's consumption" for
 * "a closed invoice never changes". Reporting both figures is what keeps that trade
 * honest rather than hidden.
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

        /**
         * Aggregates summary lines into a complete summary.
         *
         * The total is the sum of the line amounts, and each line amount is the sum of
         * its transactions' amounts. No step rounds anything a second time, so the
         * arithmetic closes exactly at every level.
         */
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
                // Deterministic ordering: this period's consumption first, then
                // adjustments oldest-first, so two runs of the same query read alike.
                lines = lines.sortedWith(
                    compareBy({ it.isAdjustment }, { it.originPeriod }, { it.transactionCode })
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
