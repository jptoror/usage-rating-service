package com.revenium.usage.rating.domain

import java.math.BigDecimal
import java.time.Instant

/**
 * An accepted event, in the shape rating needs to price it.
 *
 * Owned by the rating module and free of any persistence detail. Previously rating took
 * `processing.infrastructure.ClaimedWork` -- a JDBC row projection belonging to the
 * outbox -- which coupled a use case to another module's adapter: rating could only be
 * driven by the worker, and a change to the claim query's projection forced a change to
 * rating's public API.
 *
 * With this type the dependency runs the right way. The worker maps its claimed row into
 * this, and a replay tool or a backfill could drive rating the same way without the
 * outbox existing at all.
 */
data class RateableTransaction(
    val tenantId: String,
    val rawEventId: Long,
    val customerId: String,
    val transactionCode: String,
    /** When the usage happened: selects the pricing rule and the origin period. */
    val occurredAt: Instant,
    /** When this service first recorded it: decides whether the delivery was late. */
    val receivedAt: Instant,
    val quantity: BigDecimal,
)
