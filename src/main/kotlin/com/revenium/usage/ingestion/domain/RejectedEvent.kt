package com.revenium.usage.ingestion.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * An event that failed validation.
 *
 * Written in its own REQUIRES_NEW transaction so it survives the rollback of the
 * attempt that produced it. Without that, the record of a rejection would roll back
 * together with the thing it is evidence of, and the reconciliation report could not
 * account for what arrived.
 *
 * Nullable columns throughout: a payload malformed enough to be rejected may not carry
 * a usable event id or customer.
 */
@Entity
@Table(name = "rejected_event")
class RejectedEvent(

    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,

    @Column(name = "event_id", updatable = false)
    val eventId: UUID? = null,

    @Column(name = "customer_id", updatable = false)
    val customerId: String? = null,

    @Column(name = "transaction_code", updatable = false)
    val transactionCode: String? = null,

    @Column(name = "occurred_at", updatable = false)
    val occurredAt: Instant? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rejection_reasons", nullable = false, updatable = false, columnDefinition = "jsonb")
    val rejectionReasons: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    val payload: String,

    @Column(name = "received_at", nullable = false, updatable = false)
    val receivedAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
)
