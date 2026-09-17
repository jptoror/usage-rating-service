package com.revenium.usage.pricing.domain.model

import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.shared.domain.UnitPrice
import com.revenium.usage.tenancy.TenantId
import java.time.Instant
import java.util.Currency

/**
 * A per-unit price for a `(tenant, transactionCode)` pair over a validity window.
 *
 * Validity is `[effectiveFrom, effectiveTo)`, start inclusive and end exclusive: with a closed
 * interval an event landing on the changeover instant would match two rules and the winner
 * would depend on query order. A null `effectiveTo` means indefinitely.
 *
 * Rules are never edited or deleted — changing a price closes the current rule and inserts a
 * new one — so a rated amount is always explained by a rule that still says what it said then.
 *
 * Non-overlap is enforced by a PostgreSQL `EXCLUDE USING gist` constraint, not application
 * logic, which would race under concurrent rule creation and make rating non-deterministic.
 */
data class PricingRule(
    val tenantId: TenantId,
    val transactionCode: TransactionCode,
    val unitPrice: UnitPrice,
    val currency: Currency,
    val effectiveFrom: Instant,
    /** `null` means the rule has no end date. */
    val effectiveTo: Instant? = null,
    val description: String? = null,
    val createdAt: Instant = Instant.now(),
    val id: Long = 0,
) {
    init {
        require(effectiveTo == null || effectiveTo.isAfter(effectiveFrom)) {
            "A pricing rule must end after it begins, was [$effectiveFrom, $effectiveTo)"
        }
    }

    /** Whether this rule governs [instant]: start inclusive, end exclusive; both tested. */
    fun appliesAt(instant: Instant): Boolean =
        !instant.isBefore(effectiveFrom) && (effectiveTo == null || instant.isBefore(effectiveTo))
}
