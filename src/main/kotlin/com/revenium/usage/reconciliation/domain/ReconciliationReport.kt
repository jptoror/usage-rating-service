package com.revenium.usage.reconciliation.domain

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import java.math.BigDecimal
import java.time.Instant

/**
 * Where a received event ended up. Every event is in exactly one of these, which is what lets
 * the report's totals balance, and they are derived from the tables rather than stored on a
 * status column, so there is no second copy of the truth to drift.
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
 * Reconciliation evidence for a customer and period: does what we received account for what we
 * billed? [isBalanced] evaluates two identities a reviewer can check by hand —
 * `received = accepted + duplicates + rejected` and
 * `accepted = rated + invoiced + unrated + failed + quarantined` — and says so when one fails,
 * rather than presenting plausible-looking numbers.
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

    /** Whether the identities above hold; [imbalanceDescription] prints both sides when not. */
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
 * One rated transaction, carrying everything needed to re-derive the amount by hand:
 * `quantity x unitPrice`, the rule that supplied the price, and the originating event id.
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
