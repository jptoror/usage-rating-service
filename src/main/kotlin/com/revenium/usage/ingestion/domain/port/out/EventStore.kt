package com.revenium.usage.ingestion.domain.port.out

import com.revenium.usage.ingestion.domain.model.EventConflict
import com.revenium.usage.ingestion.domain.model.RawEvent
import com.revenium.usage.ingestion.domain.model.RejectedEvent
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.tenancy.TenantId

/**
 * Records accepted events and the evidence around them.
 *
 * An outbound port exposing only what ingestion performs. Deliberately no delete: raw
 * events are append-only evidence, and the cheapest way to guarantee that is to make
 * removal unreachable.
 */
interface EventStore {

    /**
     * Records a new event, surfacing a duplicate as a constraint violation rather than
     * a return value.
     *
     * Detection is an INSERT that fails, not a SELECT then an INSERT: a read-then-write
     * has a race window that concurrent delivery of the same event will find. The write
     * is flushed so the violation surfaces here rather than at commit.
     */
    fun record(event: RawEvent): RawEvent

    fun findByEventId(tenant: TenantId, eventId: EventId): RawEvent?

    /** Persists the incremented duplicate tally on an event already recorded. */
    fun recordDuplicateDelivery(event: RawEvent): RawEvent

    fun recordConflict(conflict: EventConflict): EventConflict

    fun recordRejection(rejected: RejectedEvent): RejectedEvent
}
