package com.revenium.usage.ingestion.domain.port.out

import com.revenium.usage.tenancy.TenantId

/**
 * Queues an accepted event for rating.
 *
 * A port owned by ingestion, named for what ingestion needs rather than for how it is
 * implemented. Ingestion previously injected `processing`'s `JpaRepository` directly,
 * which coupled it to the outbox's persistence and handed it `delete` and `deleteAll` on
 * a durable work queue it has no business modifying.
 *
 * The implementation must enqueue **in the caller's transaction**, not a new one. That
 * is the whole guarantee of the outbox pattern: the event and its work commit together,
 * so there is no window where an event is accepted but its rating is lost.
 */
interface RatingQueue {

    /** Enqueues [rawEventId] for rating, within the caller's transaction. */
    fun enqueue(tenant: TenantId, rawEventId: Long)
}
