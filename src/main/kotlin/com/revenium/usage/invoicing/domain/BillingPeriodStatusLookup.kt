package com.revenium.usage.invoicing.domain

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.TenantId

/**
 * Whether a customer's billing period has been closed.
 *
 * A port owned by the rating side of the design, named for the single question rating
 * needs answered. Rating depends on this rather than on the invoicing module's
 * internals, so the late-arrival rule stays unit-testable with a one-line lambda.
 */
interface BillingPeriodStatusLookup {

    /**
     * `true` when the period is closed and must not be modified.
     *
     * A period with no invoice row at all is open: usage arriving for a period nobody
     * has closed yet is ordinary, on-time usage.
     */
    fun isClosed(tenant: TenantId, customer: CustomerId, period: BillingPeriod): Boolean
}
