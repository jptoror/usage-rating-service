package com.revenium.usage.processing.domain.port.out

import com.revenium.usage.processing.domain.model.OutboxMessage
import com.revenium.usage.tenancy.TenantId

/**
 * Persists and retrieves queued rating work.
 *
 * An outbound port exposing only what the outbox performs. Deliberately no delete: a
 * durable work queue that anything can empty is not durable, and a message is retired by
 * reaching a terminal status rather than by disappearing.
 */
interface OutboxMessageStore {

    fun enqueue(message: OutboxMessage): OutboxMessage

    fun findByRawEventId(tenant: TenantId, rawEventId: Long): OutboxMessage?

    /** Persists a message whose status has already been decided by the domain. */
    fun save(message: OutboxMessage): OutboxMessage
}
