package com.revenium.usage.ingestion

import com.revenium.usage.ingestion.application.IngestionService
import com.revenium.usage.ingestion.domain.IngestionResult
import com.revenium.usage.ingestion.domain.RawTransactionInput
import com.revenium.usage.ingestion.infrastructure.EventConflictRepository
import com.revenium.usage.ingestion.infrastructure.RawEventRepository
import com.revenium.usage.ingestion.infrastructure.RejectedEventRepository
import com.revenium.usage.processing.domain.OutboxStatus
import com.revenium.usage.processing.infrastructure.OutboxMessageRepository
import com.revenium.usage.support.IntegrationTest
import com.revenium.usage.tenancy.TenantContext
import com.revenium.usage.tenancy.TenantId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises ingestion against a real PostgreSQL, where the constraints that actually
 * enforce the financial invariants live.
 */
@IntegrationTest
class IngestionIntegrationTest(
    @Autowired val ingestionService: IngestionService,
    @Autowired val rawEvents: RawEventRepository,
    @Autowired val outbox: OutboxMessageRepository,
    @Autowired val conflicts: EventConflictRepository,
    @Autowired val rejectedEvents: RejectedEventRepository,
    @Autowired val jdbc: JdbcTemplate,
) {

    private val tenantA = TenantId("tenant-a")
    private val tenantB = TenantId("tenant-b")

    @AfterEach
    fun cleanUp() {
        TenantContext.clear()
        // DELETE rather than TRUNCATE: the application role is deliberately not the
        // table owner, and TRUNCATE requires ownership. Scoped per tenant because RLS
        // applies to this connection too -- a delete with no tenant in scope silently
        // removes nothing, which would leave rows behind and make later tests fail in
        // confusing ways.
        listOf(tenantA, tenantB).forEach { tenant ->
            TenantContext.runAs(tenant) {
                jdbc.execute("DELETE FROM outbox_message")
                jdbc.execute("DELETE FROM event_conflict")
                jdbc.execute("DELETE FROM rated_transaction")
                jdbc.execute("DELETE FROM rejected_event")
                jdbc.execute("DELETE FROM raw_event")
            }
        }
    }

    private fun input(
        eventId: UUID = UUID.randomUUID(),
        tenantId: String = "tenant-a",
        customerId: String = "customer-42",
        quantity: String = "2",
        payloadHash: String = "hash-1",
    ) = RawTransactionInput(
        eventId = eventId.toString(),
        tenantId = tenantId,
        customerId = customerId,
        transactionCode = "VEHICLE_REGISTRATION",
        occurredAt = "2026-08-15T14:22:31Z",
        quantity = BigDecimal(quantity),
        rawPayload = """{"eventId":"$eventId","source":"upstream-api"}""",
        payloadHash = payloadHash,
    )

    // --- the accepted path -------------------------------------------------

    @Test
    fun `persists the event and its outbox message together`() {
        val eventId = UUID.randomUUID()

        val result = TenantContext.runAs(tenantA) { ingestionService.ingest(input(eventId)) }

        val accepted = assertIs<IngestionResult.Accepted>(result)
        TenantContext.runAs(tenantA) {
            val stored = assertNotNull(rawEvents.findByTenantIdAndEventId(tenantA.value, eventId))
            assertEquals("customer-42", stored.customerId)

            // Both writes committed, which is the guarantee the outbox pattern rests
            // on: an accepted event never loses its rating work.
            val queued = assertNotNull(outbox.findByTenantIdAndRawEventId(tenantA.value, accepted.rawEventId))
            assertEquals(OutboxStatus.PENDING, queued.status)
        }
    }

    @Test
    fun `preserves the original payload verbatim`() {
        val eventId = UUID.randomUUID()

        TenantContext.runAs(tenantA) {
            ingestionService.ingest(input(eventId))
            val stored = assertNotNull(rawEvents.findByTenantIdAndEventId(tenantA.value, eventId))
            // The evidence a reviewer traces an invoice back to must survive the round
            // trip through JSONB unchanged.
            assertTrue(stored.payload.contains("upstream-api"))
            assertTrue(stored.payload.contains(eventId.toString()))
        }
    }

    // --- idempotency -------------------------------------------------------

    @Test
    fun `a sequential re-delivery creates no second charge`() {
        val eventId = UUID.randomUUID()

        val first = TenantContext.runAs(tenantA) { ingestionService.ingest(input(eventId)) }
        val second = TenantContext.runAs(tenantA) { ingestionService.ingest(input(eventId)) }

        val accepted = assertIs<IngestionResult.Accepted>(first)
        val duplicate = assertIs<IngestionResult.Duplicate>(second)
        assertEquals(accepted.rawEventId, duplicate.rawEventId)

        assertEquals(1, countRawEvents(tenantA))
        assertEquals(1, countOutboxMessages(tenantA))
    }

    @Test
    fun `concurrent delivery of one event yields exactly one accepted result`() {
        // The test that matters for at-least-once delivery. Calling twice in sequence
        // proves nothing about a race: these ten threads are released simultaneously
        // and genuinely contend on the unique constraint.
        val eventId = UUID.randomUUID()
        val threads = 10
        val executor = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        try {
            val futures = (1..threads).map {
                executor.submit<IngestionResult> {
                    start.await()
                    TenantContext.runAs(tenantA) { ingestionService.ingest(input(eventId)) }
                }
            }
            start.countDown()

            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it is IngestionResult.Accepted })
            assertEquals(threads - 1, results.count { it is IngestionResult.Duplicate })

            // What the invariant actually means: one event, one charge, one unit of work.
            assertEquals(1, countRawEvents(tenantA))
            assertEquals(1, countOutboxMessages(tenantA))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `records a conflict when a re-delivery carries a different payload`() {
        val eventId = UUID.randomUUID()

        TenantContext.runAs(tenantA) {
            ingestionService.ingest(input(eventId, payloadHash = "original"))
            val result = ingestionService.ingest(input(eventId, payloadHash = "different"))

            assertTrue(assertIs<IngestionResult.Duplicate>(result).conflictingPayload)
            // First delivery wins, but the discrepancy is durable evidence.
            assertEquals(1, conflicts.countByTenantId(tenantA.value))
            assertEquals(1, countRawEvents(tenantA))
        }
    }

    // --- tenant isolation --------------------------------------------------

    @Test
    fun `the same event id is independent across tenants`() {
        // Two tenants legitimately using the same upstream id must not collide: this
        // is why uniqueness is (tenant_id, event_id) and not event_id alone.
        val sharedEventId = UUID.randomUUID()

        val a = TenantContext.runAs(tenantA) { ingestionService.ingest(input(sharedEventId, tenantId = "tenant-a")) }
        val b = TenantContext.runAs(tenantB) { ingestionService.ingest(input(sharedEventId, tenantId = "tenant-b")) }

        assertIs<IngestionResult.Accepted>(a)
        assertIs<IngestionResult.Accepted>(b)
        assertEquals(1, countRawEvents(tenantA))
        assertEquals(1, countRawEvents(tenantB))
    }

    @Test
    fun `one tenant cannot read another's events`() {
        val eventId = UUID.randomUUID()
        TenantContext.runAs(tenantA) { ingestionService.ingest(input(eventId, tenantId = "tenant-a")) }

        TenantContext.runAs(tenantB) {
            // Even asking for tenant-a's row by its exact key returns nothing.
            assertEquals(null, rawEvents.findByTenantIdAndEventId(tenantA.value, eventId))
            assertEquals(0, countRawEvents(tenantB))
        }
    }

    @Test
    fun `a body naming another tenant is rejected`() {
        val result = TenantContext.runAs(tenantA) {
            ingestionService.ingest(input(tenantId = "tenant-b"))
        }

        val rejected = assertIs<IngestionResult.Rejected>(result)
        assertTrue(rejected.failures.any { it.field == "tenantId" })
        assertEquals(0, countRawEvents(tenantA))
    }

    // --- rejection evidence ------------------------------------------------

    @Test
    fun `rejected events are recorded without becoming billable usage`() {
        TenantContext.runAs(tenantA) {
            val result = ingestionService.ingest(
                input().copy(customerId = null, eventId = null)
            )

            assertIs<IngestionResult.Rejected>(result)
            assertEquals(1, rejectedEvents.countByTenantId(tenantA.value))
            assertEquals(0, countRawEvents(tenantA))
            assertEquals(0, countOutboxMessages(tenantA))
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun countRawEvents(tenant: TenantId): Long =
        TenantContext.runAs(tenant) { rawEvents.count() }

    private fun countOutboxMessages(tenant: TenantId): Long =
        TenantContext.runAs(tenant) { outbox.count() }
}
