package com.revenium.usage.invoicing.domain.port.`in`

import com.revenium.usage.invoicing.domain.model.InvoiceSummary
import com.revenium.usage.invoicing.domain.model.UsageSummary
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId

/**
 * Reports what a customer owes for a period.
 *
 * An inbound port: the HTTP layer — or a report generator, or a test — depends on this
 * rather than on the service class.
 */
interface SummariseInvoiceUseCase {
    fun summarise(customer: CustomerId, period: BillingPeriod): InvoiceSummary

    /**
     * Totals usage across a span of periods, inclusive at both ends.
     *
     * A separate operation rather than an optional range on [summarise]: the result is
     * deliberately a [UsageSummary] and not an invoice, because a range can cover both
     * closed and open periods and so has no single status to report.
     */
    fun summariseRange(customer: CustomerId, from: BillingPeriod, to: BillingPeriod): UsageSummary
}
