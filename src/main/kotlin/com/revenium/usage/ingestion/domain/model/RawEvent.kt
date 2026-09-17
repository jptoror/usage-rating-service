package com.revenium.usage.ingestion.domain.model

import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import java.time.Instant

/**
 * The accepted event, exactly as it arrived.
 *
 * Immutable by contract: nothing in the application rewrites one. Every downstream
 * artefact is derived from it and can be recomputed; this cannot, which is why it is the
 * thing a reviewer traces an invoice amount back to.
 *
 * [duplicateDeliveryCount] is the single exception, and it is additive rather than a
 * rewrite: an identical re-delivery writes nothing else — the unique constraint rejects
 * it, which is the point — so without a tally here duplicates would be invisible to the
 * reconciliation report. A counter rather than a row per delivery, because a retry storm
 * can repeat one event thousands of times.
 */
data class RawEvent(
    val tenantId: TenantId,
    val eventId: EventId,
    val customerId: CustomerId,
    val transactionCode: TransactionCode,
    /** Drives both the billing period and the pricing rule lookup. */
    val occurredAt: Instant,
    val quantity: Quantity,
    /** The original JSON body, verbatim. */
    val payload: String,
    /** Detects a re-delivery whose body differs from the first one. */
    val payloadHash: String,
    /** Drives late-arrival detection. Independent of [occurredAt]. */
    val receivedAt: Instant = Instant.now(),
    val duplicateDeliveryCount: Long = 0,
    val lastDuplicateAt: Instant? = null,
    val id: Long = 0,
) {
    init {
        require(payload.isNotBlank()) { "A raw event must preserve its payload verbatim" }
        require(payloadHash.isNotBlank()) { "payloadHash must not be blank" }
        require(duplicateDeliveryCount >= 0) { "duplicateDeliveryCount must not be negative" }
        require((duplicateDeliveryCount > 0) == (lastDuplicateAt != null)) {
            "A duplicated event records when it was last duplicated, and a unique one does not"
        }
    }

    /** Returns a copy recording another delivery of this same event. */
    fun recordDuplicateDelivery(at: Instant): RawEvent =
        copy(duplicateDeliveryCount = duplicateDeliveryCount + 1, lastDuplicateAt = at)
}
