package com.revenium.usage.invoicing.domain.port.out

import com.revenium.usage.invoicing.domain.model.Charge
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.TenantId

/**
 * Reads the charges that belong in an invoice.
 *
 * An outbound port owned by invoicing and implemented by rating. Read-only by design: the
 * append-only invariant depends on nobody but rating writing to `rated_transaction`, and
 * the cheapest way to guarantee that is to make writes unreachable from here.
 */
interface ChargeLookup {

    /** Charges billed in [period], excluding superseded rows. */
    fun findChargesFor(tenant: TenantId, customer: CustomerId, period: BillingPeriod): List<Charge>
}
