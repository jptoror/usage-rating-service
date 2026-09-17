package com.revenium.usage.invoicing.domain.port.`in`

import com.revenium.usage.invoicing.domain.model.InvoiceSummary
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
}
