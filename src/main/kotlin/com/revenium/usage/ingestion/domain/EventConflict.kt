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

/**
 * A re-delivery that reused an event id with a different body.
 *
 * This cannot be resolved automatically: accepting the second delivery would bill
 * twice, and discarding it silently would hide a genuine upstream defect. The first
 * delivery wins and the discrepancy is recorded here, where the reconciliation report
 * surfaces it for a human to decide.
 */
@Entity
@Table(name = "event_conflict")
class EventConflict(

    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,

    @Column(name = "raw_event_id", nullable = false, updatable = false)
    val rawEventId: Long,

    @Column(name = "original_payload_hash", nullable = false, updatable = false)
    val originalPayloadHash: String,

    @Column(name = "conflicting_payload_hash", nullable = false, updatable = false)
    val conflictingPayloadHash: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conflicting_payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    val conflictingPayload: String,

    @Column(name = "detected_at", nullable = false, updatable = false)
    val detectedAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
)
