package com.revenium.usage.invoicing.domain.model

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import java.util.Currency

/**
 * Usage totals across a span of billing periods.
 *
 * Deliberately not an [InvoiceSummary]. An invoice is a statement about one period and
 * carries that period's status: a range can straddle closed and open periods, so there
 * is no single status to report and calling the result an invoice would be a lie. This
 * answers "how much did this customer use between X and Y", which is a reporting
 * question, not a billing one.
 *
 * Totals are per transaction code across the whole span; [periods] lists what was
 * covered so a reader can see which months contributed and which were closed.
 */
data class UsageSummary(
    val customerId: CustomerId,
    val from: BillingPeriod,
    val to: BillingPeriod,
    val currency: Currency,
    val periods: List<PeriodStatus>,
    val lines: List<UsageLine>,
    val totalAmount: Money,
    val transactionCount: Long,
) {
    init {
        require(from <= to) { "A range must end no earlier than it begins, was [$from, $to]" }
    }

    /** One period in the range, and whether its invoice was already closed. */
    data class PeriodStatus(val period: BillingPeriod, val status: InvoiceStatus)

    companion object {
        fun from(
            customer: CustomerId,
            from: BillingPeriod,
            to: BillingPeriod,
            currency: Currency,
            periods: List<PeriodStatus>,
            lines: List<UsageLine>,
        ) = UsageSummary(
            customerId = customer,
            from = from,
            to = to,
            currency = currency,
            periods = periods,
            lines = lines,
            totalAmount = Money.sum(lines.map { it.amount }, currency),
            transactionCount = lines.sumOf { it.transactionCount },
        )
    }
}

/** Charges for one transaction code, totalled across every period in the range. */
data class UsageLine(
    val transactionCode: TransactionCode,
    val transactionCount: Long,
    val totalQuantity: Quantity,
    val amount: Money,
) {
    init {
        require(transactionCount > 0) { "A usage line must cover at least one transaction" }
    }
}
