package com.revenium.usage.rating.domain.model

import com.revenium.usage.pricing.domain.model.PricingRule
import com.revenium.usage.pricing.domain.port.out.PricingRuleLookup
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
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
     * No pricing rule covered the event's `occurredAt`. Not a rejection: the rule may
     * legitimately be created later and the usage is real, so the event waits and is retried.
     */
    data class Unrated(val reason: String) : RatingOutcome

    /**
     * Too far outside its period to bill automatically — almost always an accidental
     * replay, and billing it silently would be worse than stopping to ask.
     */
    data class Quarantined(val reason: String) : RatingOutcome

    /** Already rated by an earlier delivery or another worker: normal under at-least-once. */
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
     * When this service first recorded the event: decides whether the delivery was late.
     * Independent of [occurredAt], so reprocessing an old event months later does not look
     * like a late delivery.
     */
    val receivedAt: Instant,
)

/**
 * Turns an accepted event into a priced result. Pure domain logic — everything it needs
 * arrives as a parameter, so the many boundary cases are plain unit tests.
 *
 * Prices by when the event occurred, never by now, so a replay produces the same answer as
 * the original; rounds once, in [Money.rate]; and never reopens a closed period.
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

        // Measured from receivedAt, not the event's age: the cutoff asks "did this only just
        // turn up?". Measuring age would quarantine a reprocess of old data that arrived on time.
        val deliveryDelay = Duration.between(request.occurredAt, request.receivedAt)
        if (deliveryDelay > lateArrivalCutoff) {
            return RatingOutcome.Quarantined(
                "Event arrived ${deliveryDelay.toDays()} days after it occurred, beyond the " +
                    "${lateArrivalCutoff.toDays()}-day cutoff"
            )
        }

        val amount = Money.rate(
            quantity = request.quantity,
            unitPrice = rule.unitPrice,
            currency = rule.currency,
        )

        // A closed invoice is never reopened: the charge lands in the next open period and
        // origin_period keeps the link to when the usage really happened.
        val isLate = isPeriodClosed(originPeriod)
        val billingPeriod = if (isLate) {
            nextOpenPeriodAfter(originPeriod, now, isPeriodClosed)
        } else {
            originPeriod
        }

        return RatingOutcome.Rated(
            rule = rule,
            amount = amount,
            billingPeriod = billingPeriod,
            originPeriod = originPeriod,
            isLateAdjustment = isLate,
        )
    }

    /**
     * The first period after [originPeriod] that is still open.
     *
     * The walk starts from `maxOf(originPeriod.next(), currentPeriod)` because neither bound
     * alone is safe: an operator may close the in-progress period, so starting at the current
     * one can assign the adjustment to a closed period — it failed exactly that way, violating
     * the CHECK that a late adjustment's two periods differ — while starting at
     * `originPeriod.next()` would land a January event arriving in September in a February
     * invoice closed months ago.
     *
     * [MAX_LOOKAHEAD] bounds the walk; reaching it is a configuration problem, and the last
     * candidate is returned rather than looping forever.
     */
    private fun nextOpenPeriodAfter(
        originPeriod: BillingPeriod,
        now: Instant,
        isPeriodClosed: (BillingPeriod) -> Boolean,
    ): BillingPeriod {
        val currentPeriod = BillingPeriod.of(now)
        var candidate = maxOf(originPeriod.next(), currentPeriod)

        repeat(MAX_LOOKAHEAD) {
            if (!isPeriodClosed(candidate)) return candidate
            candidate = candidate.next()
        }
        return candidate
    }

    private companion object {
        /** Two years of monthly periods: far past any plausible run of closed months. */
        const val MAX_LOOKAHEAD = 24
    }
}
