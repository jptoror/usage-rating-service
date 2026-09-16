package com.revenium.usage.reconciliation.domain

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import java.math.BigDecimal
import java.time.Instant

/**
 * Where a received event ended up.
 *
 * Every event that arrived is in exactly one of these, which is what lets the report's
 * totals balance. The states are derived from the tables rather than stored on a status
 * column, so there is no second copy of the truth to drift out of sync.
 */
enum class EventState {
    /** Failed validation. Never became billable usage. */
    REJECTED,

    /** A re-delivery of an event already recorded. No additional charge. */
    DUPLICATE,

    /** Recorded and queued, not yet rated. */
    ACCEPTED,

    /** No pricing rule covers it yet. Retried indefinitely; the rule may arrive later. */
    UNRATED,

    /** Priced, and part of an open period. */
    RATED,

    /** Priced, and part of a period that has been closed. */
    INVOICED,

    /** Exhausted its retries. Kept as an inspectable dead letter. */
    FAILED,

    /** Delivered far too late to bill automatically. Held for a human decision. */
    QUARANTINED,
}

/** How many events reached each state, and what they are worth. */
data class StateCount(
    val state: EventState,
    val count: Long,
    val amount: Money?,
)

/**
 * Reconciliation evidence for a customer and period.
 *
 * The report exists to answer one question — *does what we received account for what we
 * billed?* — and it answers it with arithmetic a reviewer can check by hand:
 *
 * ```
 * received  = accepted + duplicates + rejected
 * accepted  = rated + invoiced + unrated + failed + quarantined
 * ```
 *
 * [isBalanced] evaluates exactly those identities. When one fails there is a defect, and
 * the report says so rather than presenting plausible-looking numbers.
 */
data class ReconciliationReport(
    val customerId: CustomerId,
    val period: BillingPeriod,
    val receivedCount: Long,
    val states: List<StateCount>,
    val billedAmount: Money,
    val generatedAt: Instant,
) {

    fun countOf(state: EventState): Long = states.firstOrNull { it.state == state }?.count ?: 0

    /**
     * Whether the identities above hold.
     *
     * A reviewer should never have to trust this: [imbalanceDescription] prints both
     * sides of any equation that failed.
     */
    val isBalanced: Boolean
        get() = receivedCount == accepted + countOf(EventState.DUPLICATE) + countOf(EventState.REJECTED) &&
            accepted == ratedOrBilled + unresolved

    /** Events that were recorded as usage, whatever happened to them afterwards. */
    val accepted: Long
        get() = ratedOrBilled + unresolved

    private val ratedOrBilled: Long
        get() = countOf(EventState.RATED) + countOf(EventState.INVOICED)

    private val unresolved: Long
        get() = countOf(EventState.ACCEPTED) + countOf(EventState.UNRATED) +
            countOf(EventState.FAILED) + countOf(EventState.QUARANTINED)

    /** Human-readable detail when [isBalanced] is false. */
    fun imbalanceDescription(): String? {
        if (isBalanced) return null
        val expectedReceived = accepted + countOf(EventState.DUPLICATE) + countOf(EventState.REJECTED)
        return "received=$receivedCount but accepted+duplicates+rejected=$expectedReceived"
    }
}

/**
 * One rated transaction, traced back to the event and rule behind it.
 *
 * This is the row a reviewer lands on when following a total downwards, so it carries
 * everything needed to re-derive the amount by hand: `quantity x unitPrice`, the rule
 * that supplied the price, and the event id the figure came from.
 */
data class ReconciliationLine(
    val eventId: String,
    val rawEventId: Long,
    val transactionCode: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val amount: Money,
    val pricingRuleId: Long,
    val originPeriod: BillingPeriod,
    val billingPeriod: BillingPeriod,
    val isLateAdjustment: Boolean,
    val state: EventState,
)
