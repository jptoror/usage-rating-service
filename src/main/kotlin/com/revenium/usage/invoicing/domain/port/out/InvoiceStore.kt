package com.revenium.usage.invoicing.domain.port.out

import com.revenium.usage.invoicing.domain.model.Invoice
import com.revenium.usage.invoicing.domain.model.InvoiceLine
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.TenantId

/**
 * Persists and retrieves invoices and their lines.
 *
 * An outbound port: the domain states what it needs, `infrastructure` supplies it. Only
 * the operations invoicing actually performs — a closed invoice is never updated or
 * deleted, so neither operation is reachable from here.
 */
interface InvoiceStore {

    fun findInvoice(tenant: TenantId, customer: CustomerId, period: BillingPeriod): Invoice?

    /** The frozen lines of a closed invoice, in a stable order. */
    fun findLines(invoice: Invoice): List<InvoiceLine>

    /** Persists a closed invoice header, returning it with its generated id. */
    fun saveInvoice(invoice: Invoice): Invoice

    fun saveLines(lines: List<InvoiceLine>): List<InvoiceLine>
}
