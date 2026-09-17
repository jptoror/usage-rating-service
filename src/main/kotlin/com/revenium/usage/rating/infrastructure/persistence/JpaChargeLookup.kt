package com.revenium.usage.rating.infrastructure.persistence

import com.revenium.usage.invoicing.domain.model.Charge
import com.revenium.usage.invoicing.domain.port.out.ChargeLookup
import com.revenium.usage.rating.domain.port.out.RatedTransactionStore
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.TenantId
import org.springframework.stereotype.Repository

/**
 * Rating's implementation of invoicing's [ChargeLookup] port.
 *
 * Built on rating's own port rather than on the JPA repository directly, so the
 * persistence entity stays inside the persistence package.
 */
@Repository
class JpaChargeLookup(
    private val ratedTransactions: RatedTransactionStore,
) : ChargeLookup {

    override fun findChargesFor(
        tenant: TenantId,
        customer: CustomerId,
        period: BillingPeriod,
    ): List<Charge> =
        ratedTransactions.findForBillingPeriod(tenant, customer, period).map { rated ->
            Charge(
                transactionCode = rated.transactionCode,
                quantity = rated.quantity,
                amount = rated.amount,
                originPeriod = rated.originPeriod,
                isLateAdjustment = rated.isLateAdjustment,
            )
        }
}
