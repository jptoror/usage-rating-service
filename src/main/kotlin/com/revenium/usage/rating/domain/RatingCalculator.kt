package com.revenium.usage.rating.domain

import com.revenium.usage.pricing.domain.PricingRule
import com.revenium.usage.pricing.domain.PricingRuleLookup
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.shared.domain.UnitPrice
import com.revenium.usage.tenancy.TenantId
import java.time.Duration
import java.time.Instant

/** What rating a single event produced. */
sealed interface RatingOutcome {

    /** Priced successfully. */
    data class Rated(
        val rule: PricingRule,
        val amount: Money,
        val billingPeriod: BillingPeriod,
        val originPeriod: BillingPeriod,
        val isLateAdjustment: Boolean,
    ) : RatingOutcome

    /**
     * No pricing rule covered the event's `occurredAt`.
     *
     * Not an error, and deliberately not a rejection: the rule may legitimately be
     * created later, and the usage is real. The event waits and is retried.
     */
    data class Unrated(val reason: String) : RatingOutcome

    /**
     * Too far outside its period to bill automatically.
     *
     * An event this old almost always means an accidental replay, and billing it
     * silently would be worse than stopping to ask.
     */
    data class Quarantined(val reason: String) : RatingOutcome

    /**
     * Already rated by an earlier delivery or another worker.
     *
     * A normal outcome under at-least-once delivery, not an error: the work is done and
     * the message is simply marked complete.
     */
    data class AlreadyRated(val ratedTransactionId: Long) : RatingOutcome
}

/** The input rating needs, independent of how the event was stored. */
data class RatingRequest(
    val tenant: TenantId,
    val transactionCode: TransactionCode,
    val quantity: Quantity,
    /** When the usage happened: selects the pricing rule and the origin period. */
    val occurredAt: Instant,
    /**
     * When this service first recorded the event: decides whether the delivery was
     * late. Independent of [occurredAt], and the reason both are persisted --
     * reprocessing an old event months later must not look like a late delivery.
     */
    val receivedAt: Instant,
)

/**
 * Turns an accepted event into a priced result.
 *
 * Pure domain logic: no Spring, no database, no clock of its own. Everything it needs
 * arrives as a parameter, which is what makes the boundary cases — and there are many —
 * testable as plain unit tests.
 *
 * The rules it enforces:
 *
 * 1. **Price by when the event occurred, never by now.** An August event reprocessed in
 *    October rates at August's price. Without this, every replay would produce a
 *    different answer than the original.
 * 2. **Round once.** [Money.rate] multiplies at full precision and rounds a single time.
 * 3. **A closed period is immutable.** Late events are charged as adjustments in the
 *    open period, carrying their original period for traceability.
 */
class RatingCalculator(
    private val pricingRules: PricingRuleLookup,
    private val lateArrivalCutoff: Duration,
) {

    fun rate(
        request: RatingRequest,
        isPeriodClosed: (BillingPeriod) -> Boolean,
        now: Instant,
    ): RatingOutcome {
        val originPeriod = BillingPeriod.of(request.occurredAt)

        val rule = pricingRules.findApplicable(request.tenant, request.transactionCode, request.occurredAt)
            ?: return RatingOutcome.Unrated(
                "No pricing rule effective at ${request.occurredAt} for ${request.transactionCode}"
            )

        // The cutoff protects against accidental replays of ancient data, so it is
        // measured from when the event was RECEIVED, not from when the usage happened.
        //
        // Measuring from occurredAt would be wrong in a way that matters: reprocessing a
        // six-month-old event -- a deliberate, legitimate operation after a pricing
        // correction -- would quarantine it purely because the usage is old, even though
        // the event was received on time and has been sitting in the system since.
        //
        // What the cutoff is actually asking is "did this only just turn up?", and
        // receivedAt is the field that answers it.
        val deliveryDelay = Duration.between(request.occurredAt, request.receivedAt)
        if (deliveryDelay > lateArrivalCutoff) {
            return RatingOutcome.Quarantined(
                "Event arrived ${deliveryDelay.toDays()} days after it occurred, beyond the " +
                    "${lateArrivalCutoff.toDays()}-day cutoff"
            )
        }

        val amount = Money.rate(
            quantity = request.quantity,
            unitPrice = UnitPrice(rule.unitPrice),
            currency = rule.currencyAsCurrency(),
        )

        // A closed invoice is never reopened. The charge lands in the open period as an
        // adjustment, and origin_period keeps the link to when the usage really happened.
        val isLate = isPeriodClosed(originPeriod)
        val billingPeriod = if (isLate) openPeriodFor(now) else originPeriod

        return RatingOutcome.Rated(
            rule = rule,
            amount = amount,
            billingPeriod = billingPeriod,
            originPeriod = originPeriod,
            isLateAdjustment = isLate,
        )
    }

    /**
     * The period a late adjustment is charged in: the one containing "now".
     *
     * Not `originPeriod.next()`, which could itself be closed — an event from January
     * arriving in September would otherwise land in a February invoice that was closed
     * months ago.
     */
    private fun openPeriodFor(now: Instant): BillingPeriod = BillingPeriod.of(now)
}
