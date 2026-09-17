package com.revenium.usage.ingestion.domain.model

import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import java.time.Instant

/**
 * An event that failed validation.
 *
 * Recorded in its own transaction so it survives the rollback of the attempt that
 * produced it. Without that, the record of a rejection would roll back together with the
 * thing it is evidence of, and the reconciliation report could not account for what
 * arrived.
 *
 * Nullable fields throughout: a payload malformed enough to be rejected may not carry a
 * usable event id or customer.
 */
data class RejectedEvent(
    val tenantId: TenantId,
    val eventId: EventId? = null,
    val customerId: CustomerId? = null,
    val transactionCode: TransactionCode? = null,
    val occurredAt: Instant? = null,
    /** The reasons, as the JSON the report reads back. */
    val rejectionReasons: String,
    val payload: String,
    val receivedAt: Instant = Instant.now(),
    val id: Long = 0,
) {
    init {
        require(rejectionReasons.isNotBlank()) { "A rejection must record why" }
    }
}
