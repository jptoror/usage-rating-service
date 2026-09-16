package com.revenium.usage.pricing.domain

import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import java.time.Instant

/**
 * Resolves the pricing rule governing an event.
 *
 * A port owned by the domain, named for what the consumer needs rather than for how it
 * is implemented. Rating depends on this interface, never on JPA, which is what keeps
 * the rating logic unit-testable with no database and no Spring context.
 *
 * Read-only by design: rating has no business creating or amending prices, so it is not
 * handed the ability to.
 */
interface PricingRuleLookup {

    /**
     * The rule in effect for [code] at [occurredAt], or `null` when none covers it.
     *
     * Resolution is by **when the event occurred**, never by "now": an August event
     * reprocessed in October must rate at August's price, or every replay would produce
     * a different answer than the original and reconciliation would be meaningless.
     *
     * Returns at most one rule. That is guaranteed by the database's non-overlap
     * constraint, not by this method picking a winner.
     */
    fun findApplicable(tenant: TenantId, code: TransactionCode, occurredAt: Instant): PricingRule?
}
