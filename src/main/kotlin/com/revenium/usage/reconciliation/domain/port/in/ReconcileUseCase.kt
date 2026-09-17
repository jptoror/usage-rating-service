package com.revenium.usage.reconciliation.domain.port.`in`

import com.revenium.usage.reconciliation.domain.ReconciliationLine
import com.revenium.usage.reconciliation.domain.ReconciliationReport
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId

/**
 * Produces reconciliation evidence: where every received event ended up, and how a billed
 * total traces back to the events and rules behind it.
 *
 * An inbound port, so the HTTP layer depends on the two questions the report answers
 * rather than on the service class and its JDBC.
 */
interface ReconcileUseCase {

    /** Counts of every state for a customer and period, plus the amount billed. */
    fun report(customer: CustomerId, period: BillingPeriod): ReconciliationReport

    /**
     * Every rated transaction behind a period's total, traced to its event and rule.
     *
     * [transactionCode] has no default value: this bean is proxied for both
     * `@RequiresTenant` and `@Transactional`, and a default on a proxied method makes
     * Kotlin emit a synthetic `DefaultConstructorMarker` parameter that Spring tries to
     * autowire.
     */
    fun lines(
        customer: CustomerId,
        period: BillingPeriod,
        transactionCode: String?,
    ): List<ReconciliationLine>
}
