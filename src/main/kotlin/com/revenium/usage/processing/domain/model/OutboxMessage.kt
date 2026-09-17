package com.revenium.usage.processing.domain.model

import com.revenium.usage.tenancy.TenantId
import java.time.Duration
import java.time.Instant

/**
 * Where a message sits in its processing lifecycle. Persisted as a string, not an ordinal:
 * inserting a constant in the middle would otherwise reinterpret every existing row and
 * rewrite the processing history of already-billed data. A CHECK constraint mirrors these names.
 */
enum class OutboxStatus {
    /** Waiting to be claimed. */
    PENDING,

    /** Claimed by a worker. A crash leaves rows here; they are reclaimed after a timeout. */
    PROCESSING,

    /** Successfully rated. Terminal. */
    DONE,

    /**
     * No pricing rule covered the event's `occurredAt`. Not a failure — the rule may be
     * created later — so it retries slowly and indefinitely, off the attempt budget.
     */
    UNRATED,

    /** Exhausted its retries. Terminal, but kept as an inspectable dead letter. */
    FAILED,

    /** Suspiciously far outside its period, typically a replay. Held for a human decision. */
    QUARANTINED,
}

/**
 * A unit of rating work, durably queued. Written in the same transaction as the raw event it
 * refers to, so an event is never accepted with its work lost.
 *
 * Workers claim rows with `FOR UPDATE SKIP LOCKED`, taking disjoint batches. Delivery is
 * at-least-once — a worker that dies mid-transaction leaves its row claimable again — so the
 * rating step must be idempotent, which the partial unique index on rated transactions makes it.
 */
data class OutboxMessage(
    val tenantId: TenantId,
    val rawEventId: Long,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAt: Instant = Instant.now(),
    val lastError: String? = null,
    /**
     * The instance that last claimed this message. Evidence only — nothing reads it to decide
     * anything; it exists so a multi-instance run can be shown to have split the work.
     */
    val processedBy: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val id: Long = 0,
) {
    init {
        require(attemptCount >= 0) { "attemptCount must not be negative" }
    }

    /** Returns a copy marked successfully processed. */
    fun markDone(now: Instant): OutboxMessage =
        copy(status = OutboxStatus.DONE, lastError = null, updatedAt = now)

    /** Records a failed attempt: exponential backoff, or dead-letter once the budget runs out. */
    fun markFailed(
        now: Instant,
        error: String,
        maxAttempts: Int,
        backoffBase: Duration,
    ): OutboxMessage {
        val attempts = attemptCount + 1
        val base = copy(
            attemptCount = attempts,
            lastError = error.take(MAX_ERROR_LENGTH),
            updatedAt = now,
        )
        return if (attempts >= maxAttempts) {
            base.copy(status = OutboxStatus.FAILED)
        } else {
            base.copy(
                status = OutboxStatus.PENDING,
                nextAttemptAt = now.plus(backoffBase.multipliedBy(1L shl (attempts - 1))),
            )
        }
    }

    /**
     * Records that no pricing rule applied yet. Deliberately does not increment [attemptCount]:
     * a missing rule is a configuration gap, and counting it would dead-letter good usage.
     */
    fun markUnrated(now: Instant, retryDelay: Duration): OutboxMessage = copy(
        status = OutboxStatus.UNRATED,
        lastError = "No pricing rule effective at the event's occurredAt",
        nextAttemptAt = now.plus(retryDelay),
        updatedAt = now,
    )

    /** Returns a copy held for human review instead of billed. */
    fun markQuarantined(now: Instant, reason: String): OutboxMessage = copy(
        status = OutboxStatus.QUARANTINED,
        lastError = reason.take(MAX_ERROR_LENGTH),
        updatedAt = now,
    )

    private companion object {
        const val MAX_ERROR_LENGTH = 2000
    }
}
