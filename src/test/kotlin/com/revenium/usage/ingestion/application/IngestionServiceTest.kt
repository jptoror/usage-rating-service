package com.revenium.usage.ingestion.application

import tools.jackson.databind.ObjectMapper
import com.revenium.usage.ingestion.domain.model.EventConflict
import com.revenium.usage.ingestion.domain.model.IngestionResult
import com.revenium.usage.ingestion.domain.model.RawEvent
import com.revenium.usage.ingestion.domain.model.RawTransactionInput
import com.revenium.usage.ingestion.domain.model.TransactionValidator
import com.revenium.usage.ingestion.domain.model.RejectedEvent
import com.revenium.usage.ingestion.domain.port.out.EventStore
import com.revenium.usage.ingestion.domain.port.out.RatingQueue
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantContext
import com.revenium.usage.tenancy.TenantId
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IngestionServiceTest {

    private val now = Instant.parse("2026-09-16T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tenant = TenantId("tenant-a")
    private val eventUuid = UUID.fromString("73d4e120-77d0-4f11-a6d2-f3b43b430d9c")

    // One port covering every write ingestion makes. Stubbed explicitly rather than
    // relaxed: a relaxed mock returns a bare Object, which fails with a
    // ClassCastException only once the call is actually exercised.
    private val events = mockk<EventStore> {
        // The duplicate path increments a delivery counter on the existing event, so
        // that an identical re-delivery is still visible to reconciliation.
        every { recordDuplicateDelivery(any()) } answers { firstArg() }
        every { recordConflict(any()) } answers { firstArg() }
        every { recordRejection(any()) } answers { firstArg() }
    }

    // The port ingestion owns, not the outbox's repository: ingestion states what it
    // needs (enqueue for rating) and processing supplies it.
    private val ratingQueue = mockk<RatingQueue>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private val service = IngestionService(
        validator = TransactionValidator(clock, java.time.Duration.ofMinutes(5)),
        eventRecorder = EventRecorder(events, ratingQueue, clock),
        duplicateResolver = DuplicateResolver(events, clock),
        rejectionRecorder = RejectionRecorder(events, objectMapper, clock),
        clock = clock,
    )

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    private fun input(
        eventId: String? = eventUuid.toString(),
        customerId: String? = "customer-42",
        quantity: BigDecimal? = BigDecimal("2"),
        payloadHash: String = "hash-1",
    ) = RawTransactionInput(
        eventId = eventId,
        tenantId = "tenant-a",
        customerId = customerId,
        transactionCode = "VEHICLE_REGISTRATION",
        occurredAt = "2026-08-15T14:22:31Z",
        quantity = quantity,
        rawPayload = """{"eventId":"$eventId"}""",
        payloadHash = payloadHash,
    )

    private fun storedEvent(id: Long = 1L, payloadHash: String = "hash-1") = RawEvent(
        tenantId = tenant,
        eventId = EventId(eventUuid),
        customerId = CustomerId("customer-42"),
        transactionCode = TransactionCode("VEHICLE_REGISTRATION"),
        occurredAt = Instant.parse("2026-08-15T14:22:31Z"),
        quantity = Quantity(BigDecimal("2")),
        payload = "{}",
        payloadHash = payloadHash,
        receivedAt = now,
        id = id,
    )

    private fun <T> asTenant(block: () -> T): T = TenantContext.runAs(tenant, block)

    // --- accepting ---------------------------------------------------------

    @Test
    fun `records the event and queues it for rating in one step`() {
        every { events.record(any()) } returns storedEvent(id = 42L)

        val result = asTenant { service.ingest(input()) }

        val accepted = assertIs<IngestionResult.Accepted>(result)
        assertEquals(42L, accepted.rawEventId)
        assertEquals(now, accepted.receivedAt)

        // The outbox row must reference the event that was just persisted: the two
        // writes are the unit that makes the outbox pattern work.
        val queuedEventId = slot<Long>()
        verify(exactly = 1) { ratingQueue.enqueue(tenant, capture(queuedEventId)) }
        assertEquals(42L, queuedEventId.captured)
    }

    @Test
    fun `stores the payload verbatim as evidence`() {
        val persisted = slot<RawEvent>()
        every { events.record(capture(persisted)) } returns storedEvent()

        asTenant { service.ingest(input()) }

        assertEquals("""{"eventId":"$eventUuid"}""", persisted.captured.payload)
        assertEquals("hash-1", persisted.captured.payloadHash)
    }

    @Test
    fun `stamps receivedAt from the injected clock`() {
        val persisted = slot<RawEvent>()
        every { events.record(capture(persisted)) } returns storedEvent()

        asTenant { service.ingest(input()) }

        assertEquals(now, persisted.captured.receivedAt)
    }

    // --- duplicates --------------------------------------------------------

    @Test
    fun `treats a constraint violation as a duplicate rather than an error`() {
        // Detection is insert-and-catch, not select-then-insert: a read-then-write has
        // a race window that concurrent delivery of the same event will find.
        every { events.record(any()) } throws DataIntegrityViolationException("uq_raw_event_tenant_event")
        every { events.findByEventId(tenant, EventId(eventUuid)) } returns storedEvent(id = 7L)

        val result = asTenant { service.ingest(input()) }

        val duplicate = assertIs<IngestionResult.Duplicate>(result)
        assertEquals(7L, duplicate.rawEventId)
        assertEquals(now, duplicate.originalReceivedAt)
        assertFalse(duplicate.conflictingPayload)
    }

    @Test
    fun `does not queue a second unit of work for a duplicate`() {
        // The invariant behind "no double billing": a re-delivery must not create more
        // rating work.
        every { events.record(any()) } throws DataIntegrityViolationException("duplicate")
        every { events.findByEventId(tenant, EventId(eventUuid)) } returns storedEvent()

        asTenant { service.ingest(input()) }

        verify(exactly = 0) { ratingQueue.enqueue(any(), any()) }
    }

    @Test
    fun `records a conflict when a duplicate carries a different payload`() {
        every { events.record(any()) } throws DataIntegrityViolationException("duplicate")
        every { events.findByEventId(tenant, EventId(eventUuid)) } returns
            storedEvent(id = 7L, payloadHash = "original-hash")

        val result = asTenant { service.ingest(input(payloadHash = "different-hash")) }

        assertTrue(assertIs<IngestionResult.Duplicate>(result).conflictingPayload)

        // The first delivery still wins -- billing the second would double-bill -- but
        // the discrepancy is evidence a human needs to see.
        val conflict = slot<EventConflict>()
        verify(exactly = 1) { events.recordConflict(capture(conflict)) }
        assertEquals("original-hash", conflict.captured.originalPayloadHash)
        assertEquals("different-hash", conflict.captured.conflictingPayloadHash)
    }

    @Test
    fun `records no conflict when a duplicate is byte-identical`() {
        every { events.record(any()) } throws DataIntegrityViolationException("duplicate")
        every { events.findByEventId(tenant, EventId(eventUuid)) } returns storedEvent(payloadHash = "hash-1")

        asTenant { service.ingest(input(payloadHash = "hash-1")) }

        verify(exactly = 0) { events.recordConflict(any()) }
    }

    @Test
    fun `surfaces an integrity violation that is not a duplicate`() {
        // A constraint violation with no matching row is a genuine integrity problem,
        // not a duplicate. Reporting it as a duplicate would hide real corruption.
        every { events.record(any()) } throws DataIntegrityViolationException("some other constraint")
        every { events.findByEventId(tenant, EventId(eventUuid)) } returns null

        assertFailsWith<IllegalStateException> { asTenant { service.ingest(input()) } }
    }

    // --- rejection ---------------------------------------------------------

    @Test
    fun `rejects an invalid transaction without recording it as usage`() {
        val result = asTenant { service.ingest(input(customerId = null)) }

        assertIs<IngestionResult.Rejected>(result)
        verify(exactly = 0) { events.record(any()) }
        verify(exactly = 0) { ratingQueue.enqueue(any(), any()) }
    }

    @Test
    fun `records rejected events as evidence`() {
        // Requirement 6 needs rejected events to be countable; dropping them silently
        // would leave the reconciliation totals unable to balance.
        asTenant { service.ingest(input(customerId = null)) }

        verify(exactly = 1) { events.recordRejection(any<RejectedEvent>()) }
    }

    @Test
    fun `reports every validation failure to the caller`() {
        val result = asTenant { service.ingest(input(eventId = null, customerId = null)) }

        val rejected = assertIs<IngestionResult.Rejected>(result)
        assertEquals(setOf("eventId", "customerId"), rejected.failures.map { it.field }.toSet())
    }

    // --- tenant scope ------------------------------------------------------

    @Test
    fun `refuses to ingest with no tenant in scope`() {
        // Defaulting to some tenant would write one tenant's usage under another's id.
        assertFailsWith<com.revenium.usage.tenancy.MissingTenantException> {
            service.ingest(input())
        }
        verify(exactly = 0) { events.record(any()) }
    }

    @Test
    fun `persists the event under the tenant in scope`() {
        val persisted = slot<RawEvent>()
        every { events.record(capture(persisted)) } returns storedEvent()

        TenantContext.runAs(TenantId("tenant-b")) {
            service.ingest(input().copy(tenantId = "tenant-b"))
        }

        assertEquals("tenant-b", persisted.captured.tenantId.value)
    }
}
