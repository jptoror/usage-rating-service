package com.revenium.usage.ingestion.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The accepted event, exactly as it arrived.
 *
 * Immutable by contract: nothing in the application updates a row of this table. Every
 * downstream artefact is derived from it and can be recomputed; this cannot, which is
 * why it is the thing a reviewer traces an invoice amount back to.
 *
 * Mapped with JPA annotations on a domain class. That is a deliberate pragmatic
 * exception to keeping the domain framework-free — the alternative, a parallel set of
 * persistence classes plus mappers, costs more than it buys at this size. It is
 * documented in the README as an accepted trade-off.
 */
@Entity
@Table(name = "raw_event")
class RawEvent(

    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,

    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,

    @Column(name = "customer_id", nullable = false, updatable = false)
    val customerId: String,

    @Column(name = "transaction_code", nullable = false, updatable = false)
    val transactionCode: String,

    /** Drives both the billing period and the pricing rule lookup. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant,

    @Column(name = "quantity", nullable = false, updatable = false, precision = 19, scale = 6)
    val quantity: BigDecimal,

    /** The original JSON body, verbatim. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    val payload: String,

    /** Detects a re-delivery whose body differs from the first one. */
    @Column(name = "payload_hash", nullable = false, updatable = false)
    val payloadHash: String,

    /** Drives late-arrival detection. Set by the database when omitted. */
    @Column(name = "received_at", nullable = false, updatable = false)
    val receivedAt: Instant = Instant.now(),

    /**
     * How many times this event has been delivered again after the first time.
     *
     * The one mutable field on this table. An identical re-delivery writes nothing
     * else -- the unique constraint rejects it, which is the point -- so without a
     * tally here, duplicates would be invisible to the reconciliation report the
     * brief requires. A counter rather than a row per delivery: a retry storm can
     * repeat one event thousands of times.
     */
    @Column(name = "duplicate_delivery_count", nullable = false)
    var duplicateDeliveryCount: Long = 0,

    @Column(name = "last_duplicate_at")
    var lastDuplicateAt: Instant? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
) {

    /** Records another delivery of this same event. */
    fun recordDuplicateDelivery(at: Instant) {
        duplicateDeliveryCount += 1
        lastDuplicateAt = at
    }
}
