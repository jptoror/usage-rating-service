package com.revenium.usage.ingestion.infrastructure.persistence

import com.revenium.usage.ingestion.domain.model.RawEvent
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.shared.domain.Quantity
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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Persistence mapping for an accepted event.
 *
 * Mirrors the table, not the domain: primitives and nullable columns, no invariants. The
 * rules live in [RawEvent], which this converts to and from.
 */
@Entity
@Table(name = "raw_event")
class RawEventEntity(

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

    @Column(name = "payload_hash", nullable = false, updatable = false)
    val payloadHash: String,

    /** Drives late-arrival detection. Set by the database when omitted. */
    @Column(name = "received_at", nullable = false, updatable = false)
    val receivedAt: Instant = Instant.now(),

    /** The one mutable column on this table. See [RawEvent] for why it exists. */
    @Column(name = "duplicate_delivery_count", nullable = false)
    var duplicateDeliveryCount: Long = 0,

    @Column(name = "last_duplicate_at")
    var lastDuplicateAt: Instant? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
) {

    fun toDomain() = RawEvent(
        tenantId = TenantId(tenantId),
        eventId = EventId(eventId),
        customerId = CustomerId(customerId),
        transactionCode = TransactionCode(transactionCode),
        occurredAt = occurredAt,
        quantity = Quantity(quantity),
        payload = payload,
        payloadHash = payloadHash,
        receivedAt = receivedAt,
        duplicateDeliveryCount = duplicateDeliveryCount,
        lastDuplicateAt = lastDuplicateAt,
        id = id,
    )

    companion object {
        fun fromDomain(event: RawEvent) = RawEventEntity(
            tenantId = event.tenantId.value,
            eventId = event.eventId.value,
            customerId = event.customerId.value,
            transactionCode = event.transactionCode.value,
            occurredAt = event.occurredAt,
            quantity = event.quantity.value,
            payload = event.payload,
            payloadHash = event.payloadHash,
            receivedAt = event.receivedAt,
            duplicateDeliveryCount = event.duplicateDeliveryCount,
            lastDuplicateAt = event.lastDuplicateAt,
            id = event.id,
        )
    }
}
