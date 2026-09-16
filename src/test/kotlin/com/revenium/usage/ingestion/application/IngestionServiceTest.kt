package com.revenium.usage.ingestion.application

import tools.jackson.databind.ObjectMapper
import com.revenium.usage.ingestion.domain.EventConflict
import com.revenium.usage.ingestion.domain.IngestionResult
import com.revenium.usage.ingestion.domain.RawEvent
import com.revenium.usage.ingestion.domain.RawTransactionInput
import com.revenium.usage.ingestion.domain.TransactionValidator
import com.revenium.usage.ingestion.infrastructure.EventConflictRepository
import com.revenium.usage.ingestion.infrastructure.RawEventRepository
import com.revenium.usage.ingestion.infrastructure.RejectedEventRepository
import com.revenium.usage.processing.domain.OutboxMessage
import com.revenium.usage.processing.infrastructure.OutboxMessageRepository
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

    private val rawEvents = mockk<RawEventRepository>()

    // Stubbed explicitly rather than relaxed: a relaxed mock of a generic repository
    // returns a bare Object from save(), which fails with a ClassCastException only
    // once the call is actually exercised.
    private val outbox = mockk<OutboxMessageRepository> {
        every { save(any()) } answers { firstArg() }
    }
    private val conflicts = mockk<EventConflictRepository> {
        every { save(any()) } answers { firstArg() }
    }
    private val rejectedEvents = mockk<RejectedEventRepository> {
        every { save(any()) } answers { firstArg() }
    }
    private val objectMapper = ObjectMapper()

    private val service = IngestionService(
        validator = TransactionValidator(clock),
        eventRecorder = EventRecorder(rawEvents, outbox, clock),
        duplicateResolver = DuplicateResolver(rawEvents, conflicts, clock),
        rejectionRecorder = RejectionRecorder(rejectedEvents, objectMapper, clock),
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
        tenantId = tenant.value,
        eventId = eventUuid,
        customerId = "customer-42",
        transactionCode = "VEHICLE_REGISTRATION",
        occurredAt = Instant.parse("2026-08-15T14:22:31Z"),
        quantity = BigDecimal("2"),
        payload = "{}",
        payloadHash = payloadHash,
        receivedAt = now,
        id = id,
    )

    private fun <T> asTenant(block: () -> T): T = TenantContext.runAs(tenant, block)

    // --- accepting ---------------------------------------------------------

    @Test
    fun `records the event and queues it for rating in one step`() {
        every { rawEvents.saveAndFlush(any()) } returns storedEvent(id = 42L)

        val result = asTenant { service.ingest(input()) }

        val accepted = assertIs<IngestionResult.Accepted>(result)
        assertEquals(42L, accepted.rawEventId)
        assertEquals(now, accepted.receivedAt)

        // The outbox row must reference the event that was just persisted: the two
        // writes are the unit that makes the outbox pattern work.
        val queued = slot<OutboxMessage>()
        verify(exactly = 1) { outbox.save(capture(queued)) }
        assertEquals(42L, queued.captured.rawEventId)
        assertEquals(tenant.value, queued.captured.tenantId)
    }

    @Test
    fun `stores the payload verbatim as evidence`() {
        val persisted = slot<RawEvent>()
        every { rawEvents.saveAndFlush(capture(persisted)) } returns storedEvent()

        asTenant { service.ingest(input()) }

        assertEquals("""{"eventId":"$eventUuid"}""", persisted.captured.payload)
        assertEquals("hash-1", persisted.captured.payloadHash)
    }

    @Test
    fun `stamps receivedAt from the injected clock`() {
        val persisted = slot<RawEvent>()
        every { rawEvents.saveAndFlush(capture(persisted)) } returns storedEvent()

        asTenant { service.ingest(input()) }

        assertEquals(now, persisted.captured.receivedAt)
    }

    // --- duplicates --------------------------------------------------------

    @Test
    fun `treats a constraint violation as a duplicate rather than an error`() {
        // Detection is insert-and-catch, not select-then-insert: a read-then-write has
        // a race window that concurrent delivery of the same event will find.
        every { rawEvents.saveAndFlush(any()) } throws DataIntegrityViolationException("uq_raw_event_tenant_event")
        every { rawEvents.findByTenantIdAndEventId(tenant.value, eventUuid) } returns storedEvent(id = 7L)

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
        every { rawEvents.saveAndFlush(any()) } throws DataIntegrityViolationException("duplicate")
        every { rawEvents.findByTenantIdAndEventId(tenant.value, eventUuid) } returns storedEvent()

        asTenant { service.ingest(input()) }

        verify(exactly = 0) { outbox.save(any()) }
    }

    @Test
    fun `records a conflict when a duplicate carries a different payload`() {
        every { rawEvents.saveAndFlush(any()) } throws DataIntegrityViolationException("duplicate")
        every { rawEvents.findByTenantIdAndEventId(tenant.value, eventUuid) } returns
            storedEvent(id = 7L, payloadHash = "original-hash")

        val result = asTenant { service.ingest(input(payloadHash = "different-hash")) }

        assertTrue(assertIs<IngestionResult.Duplicate>(result).conflictingPayload)

        // The first delivery still wins -- billing the second would double-bill -- but
        // the discrepancy is evidence a human needs to see.
        val conflict = slot<EventConflict>()
        verify(exactly = 1) { conflicts.save(capture(conflict)) }
        assertEquals("original-hash", conflict.captured.originalPayloadHash)
        assertEquals("different-hash", conflict.captured.conflictingPayloadHash)
    }

    @Test
    fun `records no conflict when a duplicate is byte-identical`() {
        every { rawEvents.saveAndFlush(any()) } throws DataIntegrityViolationException("duplicate")
        every { rawEvents.findByTenantIdAndEventId(tenant.value, eventUuid) } returns storedEvent(payloadHash = "hash-1")

        asTenant { service.ingest(input(payloadHash = "hash-1")) }

        verify(exactly = 0) { conflicts.save(any()) }
    }

    @Test
    fun `surfaces an integrity violation that is not a duplicate`() {
        // A constraint violation with no matching row is a genuine integrity problem,
        // not a duplicate. Reporting it as a duplicate would hide real corruption.
        every { rawEvents.saveAndFlush(any()) } throws DataIntegrityViolationException("some other constraint")
        every { rawEvents.findByTenantIdAndEventId(tenant.value, eventUuid) } returns null

        assertFailsWith<IllegalStateException> { asTenant { service.ingest(input()) } }
    }

    // --- rejection ---------------------------------------------------------

    @Test
    fun `rejects an invalid transaction without recording it as usage`() {
        val result = asTenant { service.ingest(input(customerId = null)) }

        assertIs<IngestionResult.Rejected>(result)
        verify(exactly = 0) { rawEvents.saveAndFlush(any()) }
        verify(exactly = 0) { outbox.save(any()) }
    }

    @Test
    fun `records rejected events as evidence`() {
        // Requirement 6 needs rejected events to be countable; dropping them silently
        // would leave the reconciliation totals unable to balance.
        asTenant { service.ingest(input(customerId = null)) }

        verify(exactly = 1) { rejectedEvents.save(any()) }
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
        verify(exactly = 0) { rawEvents.saveAndFlush(any()) }
    }

    @Test
    fun `persists the event under the tenant in scope`() {
        val persisted = slot<RawEvent>()
        every { rawEvents.saveAndFlush(capture(persisted)) } returns storedEvent()

        TenantContext.runAs(TenantId("tenant-b")) {
            service.ingest(input().copy(tenantId = "tenant-b"))
        }

        assertEquals("tenant-b", persisted.captured.tenantId)
    }
}
