package com.revenium.usage.processing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Where a message sits in its processing lifecycle.
 *
 * Persisted as a string, not an ordinal: an ordinal silently reinterprets every
 * existing row the moment someone inserts a constant in the middle of this enum, which
 * would rewrite the processing history of already-billed data. A CHECK constraint in
 * the database mirrors these names.
 */
enum class OutboxStatus {
    /** Waiting to be claimed. */
    PENDING,

    /** Claimed by a worker. A crash leaves rows here; they are reclaimed after a timeout. */
    PROCESSING,

    /** Successfully rated. Terminal. */
    DONE,

    /**
     * No pricing rule covered the event's `occurredAt`.
     *
     * Not a failure: the rule may legitimately be created later, so this retries on a
     * slow cadence indefinitely rather than counting against the attempt budget.
     */
    UNRATED,

    /** Exhausted its retries. Terminal, but kept as an inspectable dead letter. */
    FAILED,

    /**
     * Arrived far enough outside its period to be suspicious, typically an accidental
     * replay. Held for a human decision rather than billed or discarded.
     */
    QUARANTINED,
}

/**
 * A unit of rating work, durably queued.
 *
 * Written in the **same transaction** as the `raw_event` it refers to, which is the
 * entire point of the outbox pattern: accepting an event and queueing its work either
 * both happen or neither does. There is no window in which an event is accepted but its
 * work is lost.
 *
 * Workers claim rows with `FOR UPDATE SKIP LOCKED`, so multiple instances take disjoint
 * batches without blocking one another. Delivery is at-least-once — a worker that dies
 * mid-transaction leaves its row claimable again — so the rating step must be
 * idempotent. It is, via the partial unique index on `rated_transaction`.
 */
@Entity
@Table(name = "outbox_message")
class OutboxMessage(

    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,

    @Column(name = "raw_event_id", nullable = false, updatable = false)
    val rawEventId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: OutboxStatus = OutboxStatus.PENDING,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.now(),

    @Column(name = "last_error")
    var lastError: String? = null,

    /**
     * The instance that last claimed this message.
     *
     * Evidence only -- nothing reads it to make a decision. It exists so a
     * multi-instance run can be shown to have split work across instances rather
     * than concentrating it in one.
     */
    @Column(name = "processed_by")
    var processedBy: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
) {

    /** Marks this message successfully processed. */
    fun markDone(now: Instant) {
        status = OutboxStatus.DONE
        lastError = null
        updatedAt = now
    }

    /**
     * Records a failed attempt, scheduling a retry with exponential backoff, or
     * dead-lettering once the attempt budget is exhausted.
     */
    fun markFailed(now: Instant, error: String, maxAttempts: Int, backoffBase: java.time.Duration) {
        attemptCount += 1
        lastError = error.take(MAX_ERROR_LENGTH)
        updatedAt = now
        status = if (attemptCount >= maxAttempts) {
            OutboxStatus.FAILED
        } else {
            nextAttemptAt = now.plus(backoffBase.multipliedBy(1L shl (attemptCount - 1)))
            OutboxStatus.PENDING
        }
    }

    /**
     * Records that no pricing rule applied yet.
     *
     * Deliberately does **not** increment [attemptCount]: a missing rule is a gap in
     * configuration, not a defect in the event, and retrying it for ever is correct.
     * Counting it would eventually dead-letter perfectly good usage.
     */
    fun markUnrated(now: Instant, retryDelay: java.time.Duration) {
        status = OutboxStatus.UNRATED
        lastError = "No pricing rule effective at the event's occurredAt"
        nextAttemptAt = now.plus(retryDelay)
        updatedAt = now
    }

    /** Holds this message for human review instead of billing it. */
    fun markQuarantined(now: Instant, reason: String) {
        status = OutboxStatus.QUARANTINED
        lastError = reason.take(MAX_ERROR_LENGTH)
        updatedAt = now
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 2000
    }
}
