package com.revenium.usage.pricing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * A per-unit price for a `(tenant, transactionCode)` pair over a validity window.
 *
 * Validity is `[effectiveFrom, effectiveTo)` — **start inclusive, end exclusive**. The
 * half-open interval removes any ambiguity at the changeover instant, which is exactly
 * where off-by-one-cent bugs live: with a closed interval an event landing precisely on
 * the boundary would match two rules, and which one won would depend on query order.
 *
 * `effectiveTo == null` means in effect indefinitely.
 *
 * Rules are **never edited or deleted**. Changing a price closes the current rule and
 * inserts a new one, so a rated amount can always be explained by a rule that still
 * says what it said at the time.
 *
 * Non-overlap is enforced by a PostgreSQL `EXCLUDE USING gist` constraint rather than
 * by application logic: an application-level check races under concurrent rule creation
 * and would let two overlapping rules exist, making rating non-deterministic.
 */
@Entity
@Table(name = "pricing_rule")
class PricingRule(

    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,

    @Column(name = "transaction_code", nullable = false, updatable = false)
    val transactionCode: String,

    @Column(name = "unit_price", nullable = false, updatable = false, precision = 19, scale = 6)
    val unitPrice: BigDecimal,

    // columnDefinition, not just length: the column is CHAR(3) (bpchar) and
    // Hibernate would otherwise infer varchar, which ddl-auto=validate rejects.
    @Column(name = "currency", nullable = false, updatable = false, columnDefinition = "bpchar(3)")
    val currency: String,

    @Column(name = "effective_from", nullable = false, updatable = false)
    val effectiveFrom: Instant,

    /** `null` means the rule has no end date. */
    @Column(name = "effective_to", updatable = false)
    val effectiveTo: Instant? = null,

    @Column(name = "description", updatable = false)
    val description: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
) {

    /**
     * Whether this rule governs an event that occurred at [instant].
     *
     * Start inclusive, end exclusive. Both halves of that deserve a test, and both have
     * one in `PricingRuleTest`.
     */
    fun appliesAt(instant: Instant): Boolean =
        !instant.isBefore(effectiveFrom) && (effectiveTo == null || instant.isBefore(effectiveTo))

    fun currencyAsCurrency(): Currency = Currency.getInstance(currency)
}
