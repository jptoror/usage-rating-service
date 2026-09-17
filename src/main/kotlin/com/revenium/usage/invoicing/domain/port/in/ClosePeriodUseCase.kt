package com.revenium.usage.invoicing.domain.port.`in`

import com.revenium.usage.invoicing.domain.model.InvoiceSummary
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId

/**
 * Freezes a period's totals into an invoice.
 *
 * Separate from [SummariseInvoiceUseCase] rather than another method on it: closing is an
 * administrative write with financial consequences, and a reader that only reports should
 * not be handed the ability to close.
 */
interface ClosePeriodUseCase {
    fun closePeriod(customer: CustomerId, period: BillingPeriod): InvoiceSummary
}
