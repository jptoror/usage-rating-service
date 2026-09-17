package com.revenium.usage.ingestion.infrastructure.persistence

import com.revenium.usage.ingestion.domain.model.RejectedEvent
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.shared.domain.TransactionCode
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
import java.util.UUID

/** Persistence mapping for a rejected event. Nullable columns throughout — see [RejectedEvent]. */
@Entity
@Table(name = "rejected_event")
class RejectedEventEntity(

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
) {

    fun toDomain() = RejectedEvent(
        tenantId = TenantId(tenantId),
        eventId = eventId?.let(::EventId),
        customerId = customerId?.let(::CustomerId),
        transactionCode = transactionCode?.let(::TransactionCode),
        occurredAt = occurredAt,
        rejectionReasons = rejectionReasons,
        payload = payload,
        receivedAt = receivedAt,
        id = id,
    )

    companion object {
        fun fromDomain(rejected: RejectedEvent) = RejectedEventEntity(
            tenantId = rejected.tenantId.value,
            eventId = rejected.eventId?.value,
            customerId = rejected.customerId?.value,
            transactionCode = rejected.transactionCode?.value,
            occurredAt = rejected.occurredAt,
            rejectionReasons = rejected.rejectionReasons,
            payload = rejected.payload,
            receivedAt = rejected.receivedAt,
            id = rejected.id,
        )
    }
}
