package com.revenium.usage.ingestion.domain.model

import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import java.time.Instant

/**
 * A validated usage transaction. Constructible only with every required field present and well
 * formed, so nothing downstream of validation can meet a half-valid transaction.
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
 * The outcome of an ingestion attempt. Sealed so adding a case breaks compilation at each call
 * site rather than falling through an `else`.
 */
sealed interface IngestionResult {

    /** Recorded for the first time; queued for rating. */
    data class Accepted(
        val eventId: EventId,
        val rawEventId: Long,
        val receivedAt: Instant,
    ) : IngestionResult

    /**
     * Already recorded. Not an error: the upstream retry worked as intended.
     *
     * [conflictingPayload] means the re-delivery carried a different body under the same event
     * id — an upstream bug. The first delivery still wins, since billing the second would
     * double-bill, and the discrepancy is recorded for reconciliation.
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
