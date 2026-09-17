package com.revenium.usage.processing.infrastructure

import com.revenium.usage.ingestion.domain.port.out.RatingQueue
import com.revenium.usage.processing.domain.model.OutboxMessage
import com.revenium.usage.processing.domain.port.out.OutboxMessageStore
import com.revenium.usage.tenancy.TenantId
import org.springframework.stereotype.Repository

/**
 * The outbox's implementation of ingestion's [RatingQueue] port.
 *
 * No transaction annotation of its own: it joins the caller's, which is exactly the
 * required behaviour. A `REQUIRES_NEW` here would commit the queue row separately and
 * reintroduce the window the outbox pattern exists to close.
 */
@Repository
class OutboxRatingQueue(private val messages: OutboxMessageStore) : RatingQueue {

    override fun enqueue(tenant: TenantId, rawEventId: Long) {
        messages.enqueue(OutboxMessage(tenantId = tenant, rawEventId = rawEventId))
    }
}
