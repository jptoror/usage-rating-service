package com.revenium.usage.processing

import com.revenium.usage.ingestion.application.IngestionService
import com.revenium.usage.ingestion.domain.IngestionResult
import com.revenium.usage.ingestion.domain.RawTransactionInput
import com.revenium.usage.processing.application.OutboxWorker
import com.revenium.usage.processing.domain.OutboxStatus
import com.revenium.usage.processing.infrastructure.OutboxMessageRepository
import com.revenium.usage.rating.infrastructure.RatedTransactionRepository
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
 * Exercises the full ingest → claim → rate → record path against a real PostgreSQL,
 * where `SKIP LOCKED`, the partial unique index and row-level security actually live.
 */
@IntegrationTest
class OutboxWorkerIntegrationTest(
    @Autowired val ingestionService: IngestionService,
    @Autowired val worker: OutboxWorker,
    @Autowired val outbox: OutboxMessageRepository,
    @Autowired val ratedTransactions: RatedTransactionRepository,
    @Autowired val jdbc: JdbcTemplate,
) {

    private val tenantA = TenantId("tenant-a")
    private val tenantB = TenantId("tenant-b")

    @AfterEach
    fun cleanUp() {
        TenantContext.clear()
        listOf(tenantA, tenantB).forEach { tenant ->
            TenantContext.runAs(tenant) {
                jdbc.execute("DELETE FROM outbox_message")
                jdbc.execute("DELETE FROM rated_transaction")
                jdbc.execute("DELETE FROM event_conflict")
                jdbc.execute("DELETE FROM rejected_event")
                jdbc.execute("DELETE FROM raw_event")
            }
        }
    }

    private fun ingest(
        tenant: TenantId = tenantA,
        code: String = "VEHICLE_REGISTRATION",
        occurredAt: String = "2026-08-15T14:22:31Z",
        quantity: String = "2",
    ): Long {
        val eventId = UUID.randomUUID()
        val result = TenantContext.runAs(tenant) {
            ingestionService.ingest(
                RawTransactionInput(
                    eventId = eventId.toString(),
                    tenantId = tenant.value,
                    customerId = "customer-42",
                    transactionCode = code,
                    occurredAt = occurredAt,
                    quantity = BigDecimal(quantity),
                    rawPayload = """{"eventId":"$eventId"}""",
                    payloadHash = eventId.toString(),
                )
            )
        }
        return assertIs<IngestionResult.Accepted>(result).rawEventId
    }

    /** Polls until the queue stops yielding work, so a test never depends on batch size. */
    private fun drain(maxCycles: Int = 10) {
        repeat(maxCycles) { if (worker.pollOnce() == 0) return }
    }

    // --- the happy path ----------------------------------------------------

    @Test
    fun `rates a queued event and marks the message done`() {
        val rawEventId = ingest()

        assertEquals(1, worker.pollOnce())

        TenantContext.runAs(tenantA) {
            val rated = assertNotNull(ratedTransactions.findCurrentByRawEventId(tenantA.value, rawEventId))
            // Seeded rule: 2.50 from H2 2026, quantity 2.
            assertEquals("5.0000", rated.amount.toPlainString())
            assertEquals("USD", rated.currency)

            val message = assertNotNull(outbox.findByTenantIdAndRawEventId(tenantA.value, rawEventId))
            assertEquals(OutboxStatus.DONE, message.status)
        }
    }

    @Test
    fun `records the rule that was applied and the price it carried`() {
        val rawEventId = ingest()
        worker.pollOnce()

        TenantContext.runAs(tenantA) {
            val rated = assertNotNull(ratedTransactions.findCurrentByRawEventId(tenantA.value, rawEventId))
            // Traceability: the amount can be re-derived from these two fields alone.
            assertTrue(rated.pricingRuleId > 0)
            assertEquals(0, rated.unitPrice.compareTo(BigDecimal("2.500000")))
            assertEquals(0, rated.quantity.multiply(rated.unitPrice).compareTo(rated.amount))
        }
    }

    @Test
    fun `prices events on either side of a rule changeover`() {
        // The seed changes price at 2026-07-01: 2.00 before, 2.50 from then on. These
        // two events straddle that instant by a millisecond.
        //
        // Both are dated within the 90-day arrival cutoff on purpose. An event from
        // months ago ingested today is a *late delivery* and is quarantined by design,
        // which would be testing a different rule than this one.
        val before = ingest(occurredAt = "2026-06-30T23:59:59.999Z")
        val after = ingest(occurredAt = "2026-07-01T00:00:00Z")

        drain()

        TenantContext.runAs(tenantA) {
            // Last instant under the old rule: end is exclusive, so the old price holds.
            assertEquals(
                "4.0000",
                assertNotNull(ratedTransactions.findCurrentByRawEventId(tenantA.value, before)).amount.toPlainString(),
            )
            // Exactly at the changeover: start is inclusive, so the new rule wins.
            assertEquals(
                "5.0000",
                assertNotNull(ratedTransactions.findCurrentByRawEventId(tenantA.value, after)).amount.toPlainString(),
            )
        }
    }

    @Test
    fun `quarantines an event delivered long after it occurred`() {
        // The cutoff's actual purpose: an event that only turns up months later is
        // almost always an accidental replay, and billing it silently would be worse
        // than stopping to ask.
        //
        // The event needs a rule covering its date, otherwise UNRATED is reported first
        // -- deliberately, since a missing rule names a fixable gap, while the cutoff
        // only says the delivery looks wrong.
        TenantContext.runAs(tenantA) {
            jdbc.update(
                """
                INSERT INTO pricing_rule (tenant_id, transaction_code, unit_price, currency, effective_from)
                VALUES ('tenant-a', 'ANCIENT_CODE', 1.000000, 'USD', TIMESTAMPTZ '2019-01-01 00:00:00+00')
                """
            )
        }
        val rawEventId = ingest(code = "ANCIENT_CODE", occurredAt = "2019-06-01T00:00:00Z")

        drain()

        TenantContext.runAs(tenantA) {
            val message = assertNotNull(outbox.findByTenantIdAndRawEventId(tenantA.value, rawEventId))
            assertEquals(OutboxStatus.QUARANTINED, message.status)
            // Held, not billed and not discarded: reconciliation can still account for it.
            assertEquals(null, ratedTransactions.findCurrentByRawEventId(tenantA.value, rawEventId))
        }
    }

    @Test
    fun `drains a batch of mixed tenants under each tenant's own scope`() {
        val a = ingest(tenant = tenantA)
        val b = ingest(tenant = tenantB)

        assertEquals(2, worker.pollOnce())

        // tenant-b's rule prices in EUR at 3.75: proof each message was rated under its
        // own tenant's rules, not whichever happened to be in scope.
        TenantContext.runAs(tenantA) {
            val rated = assertNotNull(ratedTransactions.findCurrentByRawEventId(tenantA.value, a))
            assertEquals("USD", rated.currency)
            assertEquals("5.0000", rated.amount.toPlainString())
        }
        TenantContext.runAs(tenantB) {
            val rated = assertNotNull(ratedTransactions.findCurrentByRawEventId(tenantB.value, b))
            assertEquals("EUR", rated.currency)
            assertEquals("7.5000", rated.amount.toPlainString())
        }
    }

    // --- idempotency and concurrency ---------------------------------------

    @Test
    fun `re-processing a message creates no second charge`() {
        val rawEventId = ingest()
        worker.pollOnce()

        // Force the message back onto the queue, as an at-least-once redelivery would.
        TenantContext.runAs(tenantA) {
            jdbc.update("UPDATE outbox_message SET status = 'PENDING' WHERE raw_event_id = ?", rawEventId)
        }
        worker.pollOnce()

        TenantContext.runAs(tenantA) {
            assertEquals(1, ratedTransactions.countCurrent(tenantA.value))
        }
    }

    @Test
    fun `concurrent workers rate each event exactly once`() {
        // The real multi-instance test: four workers polling simultaneously against one
        // queue. SKIP LOCKED must hand them disjoint batches, and the partial unique
        // index must catch anything that slips through.
        repeat(20) { ingest() }

        val workers = 4
        val executor = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)

        try {
            val futures = (1..workers).map {
                executor.submit<Int> {
                    start.await()
                    // Several cycles each, so they genuinely contend rather than one
                    // worker happening to drain the queue before the others wake.
                    (1..5).sumOf { worker.pollOnce() }
                }
            }
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }

            TenantContext.runAs(tenantA) {
                // 20 events in, 20 charges out. Not 21, not 19.
                assertEquals(20, ratedTransactions.countCurrent(tenantA.value))
                assertEquals(20, outbox.countByTenantIdAndStatus(tenantA.value, OutboxStatus.DONE))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    // --- unrated -----------------------------------------------------------

    @Test
    fun `an event with no pricing rule waits rather than failing`() {
        val rawEventId = ingest(code = "NO_SUCH_CODE")

        worker.pollOnce()

        TenantContext.runAs(tenantA) {
            val message = assertNotNull(outbox.findByTenantIdAndRawEventId(tenantA.value, rawEventId))
            assertEquals(OutboxStatus.UNRATED, message.status)
            // Not counted as an attempt: the rule may be created tomorrow, and this
            // event must still be waiting when it is.
            assertEquals(0, message.attemptCount)
            assertEquals(null, ratedTransactions.findCurrentByRawEventId(tenantA.value, rawEventId))
        }
    }

    @Test
    fun `an unrated event is rated once its pricing rule appears`() {
        val rawEventId = ingest(code = "LATE_CODE")
        worker.pollOnce()

        TenantContext.runAs(tenantA) {
            assertEquals(
                OutboxStatus.UNRATED,
                assertNotNull(outbox.findByTenantIdAndRawEventId(tenantA.value, rawEventId)).status,
            )
        }

        // The rule arrives, and the waiting event is picked up on the next cycle.
        // Written inside a tenant scope: an unscoped INSERT is refused by RLS, which is
        // the policy working as intended.
        TenantContext.runAs(tenantA) {
            jdbc.update(
                """
                INSERT INTO pricing_rule (tenant_id, transaction_code, unit_price, currency, effective_from)
                VALUES ('tenant-a', 'LATE_CODE', 1.500000, 'USD', '2026-01-01T00:00:00Z')
                """
            )
            jdbc.update("UPDATE outbox_message SET next_attempt_at = now() WHERE raw_event_id = ?", rawEventId)
        }

        worker.pollOnce()

        TenantContext.runAs(tenantA) {
            val rated = assertNotNull(ratedTransactions.findCurrentByRawEventId(tenantA.value, rawEventId))
            assertEquals("3.0000", rated.amount.toPlainString())
            assertEquals(
                OutboxStatus.DONE,
                assertNotNull(outbox.findByTenantIdAndRawEventId(tenantA.value, rawEventId)).status,
            )
        }
    }

    // --- recovery ----------------------------------------------------------

    @Test
    fun `work abandoned by a dead worker is reclaimed`() {
        // A worker killed between claiming and finishing leaves rows in PROCESSING that
        // the claim query does not look at. Without reclaim they would never be rated.
        val rawEventId = ingest()
        TenantContext.runAs(tenantA) {
            jdbc.update(
                """
                UPDATE outbox_message
                SET status = 'PROCESSING', updated_at = now() - interval '1 hour'
                WHERE raw_event_id = ?
                """,
                rawEventId,
            )
        }

        worker.pollOnce()

        TenantContext.runAs(tenantA) {
            assertEquals(
                OutboxStatus.DONE,
                assertNotNull(outbox.findByTenantIdAndRawEventId(tenantA.value, rawEventId)).status,
            )
            assertEquals(1, ratedTransactions.countCurrent(tenantA.value))
        }
    }

    // --- tenant isolation of the rated result ------------------------------

    @Test
    fun `rated transactions are invisible to other tenants`() {
        ingest(tenant = tenantA)
        worker.pollOnce()

        TenantContext.runAs(tenantB) {
            // A deliberately unfiltered query: RLS is what returns nothing here.
            val rows = jdbc.queryForList("SELECT tenant_id FROM rated_transaction")
            assertTrue(rows.none { it["tenant_id"] == "tenant-a" })
            assertEquals(0, ratedTransactions.countCurrent(tenantB.value))
        }
    }
}
