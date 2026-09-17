package com.revenium.usage.processing.domain.port.`in`

/**
 * Drains one batch of queued rating work.
 *
 * An inbound port: what drives the outbox — the scheduler in production, a test calling
 * it directly, an operator endpoint if one is ever added — depends on this rather than on
 * the worker class. The return value is the batch size, which is what lets a test drive
 * the queue deterministically instead of sleeping on a scheduler.
 */
interface DrainOutboxUseCase {
    fun pollOnce(): Int
}
