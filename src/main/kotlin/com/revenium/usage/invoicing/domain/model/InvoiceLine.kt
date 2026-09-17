package com.revenium.usage.invoicing.domain.model

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId

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
data class InvoiceLine(
    val tenantId: TenantId,
    val invoiceId: Long,
    val transactionCode: TransactionCode,
    val transactionCount: Long,
    val totalQuantity: Quantity,
    val amount: Money,
    /** The period the usage happened in, which may predate this invoice. */
    val originPeriod: BillingPeriod,
    val isAdjustment: Boolean = false,
    val id: Long = 0,
) {
    init {
        require(transactionCount > 0) { "An invoice line must cover at least one transaction" }
    }
}
