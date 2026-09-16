package com.revenium.usage.rating.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * The financial result of rating one event.
 *
 * Recomputable, unlike the raw event it derives from. Corrections never `UPDATE` an
 * amount in place: a new row is inserted and the old one points at it through
 * [supersededBy], so the history of what was billed, when, and under which rule stays
 * intact and auditable.
 *
 * ### Why the unit price is copied
 *
 * The row records both [pricingRuleId] — *which* rule applied — and [unitPrice], the
 * value that rule had at the time. The redundancy is deliberate. Pricing rules can be
 * corrected, and when one is, every amount already calculated under it must keep
 * explaining itself without depending on the current state of the pricing table.
 *
 * ### Why there are two periods
 *
 * [originPeriod] is the period the usage actually happened in; [billingPeriod] is the
 * period it is charged in. They differ only when an event arrived after its own period
 * had closed, in which case it is billed as an adjustment in the open period and
 * [isLateAdjustment] is true. A database CHECK keeps the flag and the two periods
 * consistent, so no code path can set one without the other.
 */
@Entity
@Table(name = "rated_transaction")
class RatedTransaction(

    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,

    @Column(name = "raw_event_id", nullable = false, updatable = false)
    val rawEventId: Long,

    @Column(name = "customer_id", nullable = false, updatable = false)
    val customerId: String,

    @Column(name = "transaction_code", nullable = false, updatable = false)
    val transactionCode: String,

    /** Which rule applied. */
    @Column(name = "pricing_rule_id", nullable = false, updatable = false)
    val pricingRuleId: Long,

    /** The value that rule had when it was applied. */
    @Column(name = "unit_price", nullable = false, updatable = false, precision = 19, scale = 6)
    val unitPrice: BigDecimal,

    @Column(name = "quantity", nullable = false, updatable = false, precision = 19, scale = 6)
    val quantity: BigDecimal,

    /** Rounded HALF_UP once, at scale 4, when this row was created. */
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    val amount: BigDecimal,

    // columnDefinition, not just length: the column is CHAR(3) (bpchar) and
    // Hibernate would otherwise infer varchar, which ddl-auto=validate rejects.
    @Column(name = "currency", nullable = false, updatable = false, columnDefinition = "bpchar(3)")
    val currency: String,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant,

    /** The period this amount is charged in. */
    @Column(name = "billing_period", nullable = false, updatable = false)
    val billingPeriod: LocalDate,

    /** The period the usage actually belongs to. */
    @Column(name = "origin_period", nullable = false, updatable = false)
    val originPeriod: LocalDate,

    @Column(name = "is_late_adjustment", nullable = false, updatable = false)
    val isLateAdjustment: Boolean = false,

    @Column(name = "rated_at", nullable = false, updatable = false)
    val ratedAt: Instant = Instant.now(),

    /** Set when a correction has replaced this row. */
    @Column(name = "superseded_by")
    var supersededBy: Long? = null,

    @Column(name = "superseded_reason")
    var supersededReason: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
) {

    /** Whether this row is the current one for its event. */
    val isCurrent: Boolean get() = supersededBy == null

    /**
     * Marks this row as replaced by [replacementId].
     *
     * The only mutation this entity permits, and it does not touch the amount: the
     * original figure stays readable so an auditor can see both what was billed and
     * what it was corrected to.
     */
    fun supersede(replacementId: Long, reason: String) {
        require(replacementId != id) { "A rated transaction cannot supersede itself" }
        supersededBy = replacementId
        supersededReason = reason
    }
}
