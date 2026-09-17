package com.revenium.usage.ingestion.infrastructure.persistence

import com.revenium.usage.ingestion.domain.model.EventConflict
import com.revenium.usage.tenancy.TenantId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** Persistence mapping for a payload conflict between two deliveries of one event id. */
@Entity
@Table(name = "event_conflict")
class EventConflictEntity(

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
) {

    fun toDomain() = EventConflict(
        tenantId = TenantId(tenantId),
        rawEventId = rawEventId,
        originalPayloadHash = originalPayloadHash,
        conflictingPayloadHash = conflictingPayloadHash,
        conflictingPayload = conflictingPayload,
        detectedAt = detectedAt,
        id = id,
    )

    companion object {
        fun fromDomain(conflict: EventConflict) = EventConflictEntity(
            tenantId = conflict.tenantId.value,
            rawEventId = conflict.rawEventId,
            originalPayloadHash = conflict.originalPayloadHash,
            conflictingPayloadHash = conflict.conflictingPayloadHash,
            conflictingPayload = conflict.conflictingPayload,
            detectedAt = conflict.detectedAt,
            id = conflict.id,
        )
    }
}
