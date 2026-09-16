package com.revenium.usage.ingestion.domain

import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import java.time.Instant

/**
 * A validated usage transaction, ready to be recorded.
 *
 * Constructing one is only possible with every required field present and well formed,
 * so code downstream of validation cannot encounter a half-valid transaction. Invalid
 * input never becomes a [UsageTransaction]; it becomes a [ValidationFailure].
 */
data class UsageTransaction(
    val eventId: EventId,
    val tenantId: TenantId,
    val customerId: CustomerId,
    val transactionCode: TransactionCode,
    val occurredAt: Instant,
    val quantity: Quantity,
    /** The payload exactly as received, preserved as evidence. */
    val rawPayload: String,
    val payloadHash: String,
)

/** A single reason an incoming transaction was rejected. */
data class ValidationFailure(val field: String, val reason: String) {
    override fun toString(): String = "$field: $reason"
}

/**
 * The outcome of an ingestion attempt.
 *
 * A sealed hierarchy so that every caller must handle each outcome explicitly: adding
 * a new one breaks compilation at each call site rather than silently falling through
 * an `else`.
 */
sealed interface IngestionResult {

    /** Recorded for the first time; queued for rating. */
    data class Accepted(
        val eventId: EventId,
        val rawEventId: Long,
        val receivedAt: Instant,
    ) : IngestionResult

    /**
     * Already recorded. Not an error: the upstream retry worked exactly as intended.
     *
     * [conflictingPayload] is true when the re-delivery carried a *different* body
     * under the same event id, which indicates a bug upstream. The first delivery
     * still wins — billing the second would double-bill — and the discrepancy is
     * recorded for reconciliation.
     */
    data class Duplicate(
        val eventId: EventId,
        val rawEventId: Long,
        val originalReceivedAt: Instant,
        val conflictingPayload: Boolean,
    ) : IngestionResult

    /** Failed validation. Recorded as evidence, never silently dropped. */
    data class Rejected(
        val eventId: EventId?,
        val failures: List<ValidationFailure>,
    ) : IngestionResult
}
