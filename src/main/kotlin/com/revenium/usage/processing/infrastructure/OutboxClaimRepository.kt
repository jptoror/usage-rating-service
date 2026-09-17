package com.revenium.usage.processing.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** One claimed unit of rating work, with everything the rating step needs. */
data class ClaimedWork(
    val outboxId: Long,
    val tenantId: String,
    val rawEventId: Long,
    val customerId: String,
    val transactionCode: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val quantity: java.math.BigDecimal,
    val attemptCount: Int,
)

/**
 * Claims outbox work with `FOR UPDATE SKIP LOCKED`, so N instances polling simultaneously
 * take disjoint batches with no coordination, leader election or broker.
 *
 * Raw JDBC rather than JPA because `SKIP LOCKED` is the whole mechanism and JPA's pessimistic
 * locking does not express it portably; this is also a projection over a join, not an entity.
 *
 * Delivery is at-least-once, never exactly-once: a worker that dies mid-transaction releases
 * its locks and the rows become claimable again, so the same event can be rated twice. That
 * is safe only because of the partial unique index on `rated_transaction` — the idempotency
 * is in the database, not in this query. Ordering is not preserved across workers, which is
 * fine because rating one event never depends on another.
 */
@Repository
class OutboxClaimRepository(private val jdbc: JdbcTemplate) {

    /**
     * Claims up to [batchSize] messages and marks them `PROCESSING`.
     *
     * `REQUIRES_NEW` so the claim commits on its own: one poison message must not roll back
     * the claim for the whole batch and stall every good row behind it.
     *
     * [instanceId] has no default value: a default on a method of a Spring-proxied bean makes
     * Kotlin emit a synthetic `DefaultConstructorMarker` parameter that Spring tries to
     * autowire, and the application fails to start.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimBatch(now: Instant, batchSize: Int, instanceId: String): List<ClaimedWork> {
        val claimed = jdbc.query(
            CLAIM_SQL,
            { rs, _ ->
                ClaimedWork(
                    outboxId = rs.getLong("id"),
                    tenantId = rs.getString("tenant_id"),
                    rawEventId = rs.getLong("raw_event_id"),
                    customerId = rs.getString("customer_id"),
                    transactionCode = rs.getString("transaction_code"),
                    occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                    receivedAt = rs.getTimestamp("received_at").toInstant(),
                    quantity = rs.getBigDecimal("quantity"),
                    attemptCount = rs.getInt("attempt_count"),
                )
            },
            java.sql.Timestamp.from(now),
            batchSize,
        )

        if (claimed.isNotEmpty()) {
            jdbc.update(
                MARK_PROCESSING_SQL,
                java.sql.Timestamp.from(now),
                instanceId,
                claimed.map { it.outboxId }.toTypedArray(),
            )
        }
        return claimed
    }

    /**
     * Returns messages stuck in `PROCESSING` to the queue. A worker killed mid-flight leaves
     * rows there for ever otherwise, since the claim query only looks at `PENDING`/`UNRATED`.
     *
     * The timeout must exceed the longest plausible processing time, or this reclaims work
     * still legitimately running — harmless thanks to the unique index, but wasteful.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reclaimStale(now: Instant, timeout: java.time.Duration): Int =
        jdbc.update(RECLAIM_SQL, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.minus(timeout)))

    private companion object {
        /**
         * Joins the event onto its queue row so the worker needs no second round trip per
         * message. `ORDER BY o.id` approximates arrival order within a single worker only.
         */
        const val CLAIM_SQL = """
            SELECT o.id, o.tenant_id, o.raw_event_id, o.attempt_count,
                   e.customer_id, e.transaction_code, e.occurred_at, e.received_at, e.quantity
            FROM outbox_message o
            JOIN raw_event e ON e.id = o.raw_event_id
            WHERE o.status IN ('PENDING', 'UNRATED')
              AND o.next_attempt_at <= ?
            ORDER BY o.id
            LIMIT ?
            FOR UPDATE OF o SKIP LOCKED
        """

        const val MARK_PROCESSING_SQL = """
            UPDATE outbox_message
            SET status = 'PROCESSING', updated_at = ?, processed_by = ?
            WHERE id = ANY (?)
        """

        const val RECLAIM_SQL = """
            UPDATE outbox_message
            SET status = 'PENDING', updated_at = ?
            WHERE status = 'PROCESSING' AND updated_at < ?
        """
    }
}
