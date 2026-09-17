package com.revenium.usage.rating.domain.port.out

import com.revenium.usage.rating.domain.model.RatedTransaction
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.TenantId

/**
 * Persists and retrieves rated transactions.
 *
 * An outbound port: the domain states what it needs, `infrastructure` supplies it. Only
 * the operations rating actually performs, so nothing here can delete the ledger.
 */
interface RatedTransactionStore {

    /** The current rating for an event, or null. Superseded rows are history, not charges. */
    fun findCurrent(tenant: TenantId, rawEventId: Long): RatedTransaction?

    /**
     * Persists a rating, failing if the event already has a current one.
     *
     * The uniqueness is enforced by a partial unique index, not by this method — which is
     * what makes at-least-once delivery safe.
     */
    fun save(rated: RatedTransaction): RatedTransaction

    fun findForBillingPeriod(
        tenant: TenantId,
        customer: CustomerId,
        period: BillingPeriod,
    ): List<RatedTransaction>
}
