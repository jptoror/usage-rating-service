package com.revenium.usage.ingestion.infrastructure.persistence

import com.revenium.usage.ingestion.domain.model.EventConflict
import com.revenium.usage.ingestion.domain.model.RawEvent
import com.revenium.usage.ingestion.domain.model.RejectedEvent
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventEntityTest {

    private val tenant = TenantId("tenant-a")
    private val eventId = EventId(UUID.fromString("73d4e120-77d0-4f11-a6d2-f3b43b430d9c"))
    private val now = Instant.parse("2026-09-16T12:00:00Z")

    private fun rawEvent() = RawEvent(
        tenantId = tenant,
        eventId = eventId,
        customerId = CustomerId("customer-42"),
        transactionCode = TransactionCode("VEHICLE_REGISTRATION"),
        occurredAt = Instant.parse("2026-08-15T14:22:31Z"),
        quantity = Quantity(BigDecimal("2")),
        payload = """{"eventId":"$eventId"}""",
        payloadHash = "hash-1",
        receivedAt = now,
        id = 5L,
    )

    @Test
    fun `a raw event round trips`() {
        val original = rawEvent()

        assertEquals(original, RawEventEntity.fromDomain(original).toDomain())
    }

    @Test
    fun `the payload survives the round trip byte for byte`() {
        // The evidence a reviewer traces an invoice amount back to. Anything that
        // reformats it breaks the hash, and a retry then looks like a conflict.
        val mapped = RawEventEntity.fromDomain(rawEvent()).toDomain()

        assertEquals("""{"eventId":"$eventId"}""", mapped.payload)
        assertEquals("hash-1", mapped.payloadHash)
    }

    @Test
    fun `the duplicate tally round trips`() {
        val duplicated = rawEvent().recordDuplicateDelivery(now)

        val mapped = RawEventEntity.fromDomain(duplicated).toDomain()

        assertEquals(1, mapped.duplicateDeliveryCount)
        assertEquals(now, mapped.lastDuplicateAt)
    }

    @Test
    fun `a rejected event round trips with every field absent`() {
        // A payload malformed enough to be rejected may carry no usable id at all, and
        // the rejection must still be recorded rather than lost.
        val rejected = RejectedEvent(
            tenantId = tenant,
            rejectionReasons = """[{"field":"eventId","reason":"is required"}]""",
            payload = "{}",
            receivedAt = now,
            id = 6L,
        )

        val mapped = RejectedEventEntity.fromDomain(rejected).toDomain()

        assertEquals(rejected, mapped)
        assertNull(mapped.eventId)
        assertNull(mapped.customerId)
    }

    @Test
    fun `a rejected event round trips with every field present`() {
        val rejected = RejectedEvent(
            tenantId = tenant,
            eventId = eventId,
            customerId = CustomerId("customer-42"),
            transactionCode = TransactionCode("VEHICLE_REGISTRATION"),
            occurredAt = Instant.parse("2026-08-15T14:22:31Z"),
            rejectionReasons = """[{"field":"quantity","reason":"must be positive"}]""",
            payload = "{}",
            receivedAt = now,
            id = 7L,
        )

        assertEquals(rejected, RejectedEventEntity.fromDomain(rejected).toDomain())
    }

    @Test
    fun `a conflict round trips with both payload hashes`() {
        // Both sides are the evidence: without them nobody can tell which delivery was
        // kept or what the other one said.
        val conflict = EventConflict(
            tenantId = tenant,
            rawEventId = 5L,
            originalPayloadHash = "hash-1",
            conflictingPayloadHash = "hash-2",
            conflictingPayload = """{"quantity":99}""",
            detectedAt = now,
            id = 8L,
        )

        assertEquals(conflict, EventConflictEntity.fromDomain(conflict).toDomain())
    }
}
