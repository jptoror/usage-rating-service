package com.revenium.usage.rating.domain.model

import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import java.time.Instant

/**
 * An accepted event, in the shape rating needs to price it.
 *
 * Owned by the rating module and free of persistence detail, so rating can be driven by
 * the outbox worker, a replay tool or a backfill without any of them leaking their own
 * types into it.
 */
data class RateableTransaction(
    val tenantId: TenantId,
    val rawEventId: Long,
    val customerId: CustomerId,
    val transactionCode: TransactionCode,
    /** Selects the pricing rule and the origin period. */
    val occurredAt: Instant,
    /** Decides whether the delivery was late. Independent of [occurredAt]. */
    val receivedAt: Instant,
    val quantity: Quantity,
)
