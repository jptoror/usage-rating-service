package com.revenium.usage.rating.infrastructure

import com.revenium.usage.invoicing.domain.Charge
import com.revenium.usage.invoicing.domain.ChargeLookup
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.TenantId
import org.springframework.stereotype.Repository

/**
 * Rating's implementation of invoicing's [ChargeLookup] port.
 *
 * The dependency points the right way: invoicing declares what it needs, rating supplies
 * it, and rating's entity never leaves this module.
 */
@Repository
class JpaChargeLookup(
    private val ratedTransactions: RatedTransactionRepository,
) : ChargeLookup {

    override fun findChargesFor(
        tenant: TenantId,
        customer: CustomerId,
        period: BillingPeriod,
    ): List<Charge> =
        ratedTransactions
            .findCurrentForBillingPeriod(tenant.value, customer.value, period.startDate)
            .map { rated ->
                Charge(
                    transactionCode = rated.transactionCode,
                    quantity = rated.quantity,
                    amount = rated.amount,
                    currency = rated.currency,
                    originPeriod = rated.originPeriod,
                    isLateAdjustment = rated.isLateAdjustment,
                )
            }
}
