package com.revenium.usage.processing.infrastructure.persistence

import com.revenium.usage.processing.domain.model.OutboxMessage
import com.revenium.usage.processing.domain.model.OutboxStatus
import com.revenium.usage.tenancy.TenantId
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
 * Persistence mapping for a queued unit of rating work.
 *
 * Mirrors the table, not the domain: primitives and mutable columns, no invariants. The
 * lifecycle rules live in [OutboxMessage], which this converts to and from.
 */
@Entity
@Table(name = "outbox_message")
class OutboxMessageEntity(

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

    fun toDomain() = OutboxMessage(
        tenantId = TenantId(tenantId),
        rawEventId = rawEventId,
        status = status,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        lastError = lastError,
        processedBy = processedBy,
        createdAt = createdAt,
        updatedAt = updatedAt,
        id = id,
    )

    companion object {
        fun fromDomain(message: OutboxMessage) = OutboxMessageEntity(
            tenantId = message.tenantId.value,
            rawEventId = message.rawEventId,
            status = message.status,
            attemptCount = message.attemptCount,
            nextAttemptAt = message.nextAttemptAt,
            lastError = message.lastError,
            processedBy = message.processedBy,
            createdAt = message.createdAt,
            updatedAt = message.updatedAt,
            id = message.id,
        )
    }
}
