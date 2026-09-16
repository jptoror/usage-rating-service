package com.revenium.usage.invoicing

import com.revenium.usage.ingestion.application.IngestionService
import com.revenium.usage.ingestion.domain.IngestionResult
import com.revenium.usage.ingestion.domain.RawTransactionInput
import com.revenium.usage.invoicing.application.InvoiceService
import com.revenium.usage.invoicing.domain.InvoiceStatus
import com.revenium.usage.processing.application.OutboxWorker
import com.revenium.usage.reconciliation.application.ReconciliationService
import com.revenium.usage.reconciliation.domain.EventState
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.support.IntegrationTest
import com.revenium.usage.support.PostgresContainerInitializer
import com.revenium.usage.tenancy.TenantContext
import com.revenium.usage.tenancy.TenantId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The end-to-end path a reviewer would walk: ingest, rate, summarise, close, reconcile.
 */
@IntegrationTest
class InvoicingIntegrationTest(
    @Autowired val ingestionService: IngestionService,
    @Autowired val worker: OutboxWorker,
    @Autowired val invoiceService: InvoiceService,
    @Autowired val reconciliationService: ReconciliationService,
    @Autowired val jdbc: JdbcTemplate,
) {

    private val tenantA = TenantId("tenant-a")
    private val tenantB = TenantId("tenant-b")
    /**
     * A customer unique to each test method.
     *
     * Sharing one customer made these tests order-dependent: closing a period in one
     * test moved another test's charges from RATED to INVOICED, so a test that passed in
     * isolation failed in the suite. Per-test isolation is cheaper than reasoning about
     * which test may have closed what.
     */
    // A value class cannot be `lateinit`, so this is assigned a placeholder and
    // replaced per test.
    private var customer: CustomerId = CustomerId("unassigned")

    @BeforeEach
    fun assignCustomer() {
        customer = CustomerId("inv-${System.nanoTime()}")
    }

    /** The period containing "now", so events fall inside the arrival cutoff. */
    private val currentPeriod = BillingPeriod(YearMonth.now(ZoneOffset.UTC))

    /**
     * Cleans BEFORE each test as well as after.
     *
     * Cleaning only afterwards leaves the first test of each class inheriting whatever
     * the previous class left behind — the container is shared by the whole suite. Tests
     * that count rows for a tenant then see a number that depends on execution order.
     */
    @BeforeEach
    fun startFromAnEmptyDatabase() {
        TenantContext.clear()
        PostgresContainerInitializer.CLEANER.clear()
    }

    @AfterEach
    fun cleanUp() {
        TenantContext.clear()
        // Cleared as the OWNER, in one pass: a per-tenant DELETE is scoped by row-level
        // security, so rows belonging to a tenant this class does not know about survive
        // and then block the foreign key on raw_event. That failed in CI while passing
        // locally, which is the worst way to find out.
        PostgresContainerInitializer.CLEANER.clear()
    }

    private fun ingest(
        tenant: TenantId = tenantA,
        code: String = "VEHICLE_REGISTRATION",
        occurredAt: Instant = currentPeriod.start.plusSeconds(3600),
        quantity: String = "2",
        customerId: String = customer.value,
    ): IngestionResult {
        val eventId = UUID.randomUUID()
        return TenantContext.runAs(tenant) {
            ingestionService.ingest(
                RawTransactionInput(
                    eventId = eventId.toString(),
                    tenantId = tenant.value,
                    customerId = customerId,
                    transactionCode = code,
                    occurredAt = occurredAt.toString(),
                    quantity = BigDecimal(quantity),
                    rawPayload = """{"eventId":"$eventId"}""",
                    payloadHash = eventId.toString(),
                )
            )
        }
    }

    /**
     * Polls until every message for this test's customer has reached a terminal state.
     *
     * Not "until pollOnce returns 0": that reports how many messages were CLAIMED, and a
     * claimed message is still in flight. Stopping there asserted against a half-rated
     * period and failed intermittently depending on timing.
     */
    private fun drain(maxCycles: Int = 30) {
        repeat(maxCycles) {
            worker.pollOnce()
            val outstanding = TenantContext.runAs(tenantA) {
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM outbox_message o
                    JOIN raw_event e ON e.id = o.raw_event_id
                    WHERE e.customer_id = ? AND o.status NOT IN ('DONE','UNRATED','QUARANTINED','FAILED')
                    """,
                    Long::class.java,
                    customer.value,
                ) ?: 0
            }
            if (outstanding == 0L) return
        }
    }

    // --- summaries ---------------------------------------------------------

    @Test
    fun `summarises an open period from the latest rated transactions`() {
        repeat(3) { ingest() }
        drain()

        val summary = TenantContext.runAs(tenantA) {
            invoiceService.summarise(customer, currentPeriod)
        }

        // 3 x (2 units at 2.50)
        assertEquals("15.0000", summary.totalAmount.amount.toPlainString())
        assertEquals(3, summary.transactionCount)
        assertEquals(InvoiceStatus.OPEN, summary.status)
    }

    @Test
    fun `the total always equals the sum of the lines`() {
        // Checked against a fractional price, where a second rounding step would show up
        // as a discrepancy between the header and its own lines.
        repeat(3) { ingest(code = "RECORD_LOOKUP", quantity = "7") }
        ingest(code = "TITLE_TRANSFER")
        drain()

        val summary = TenantContext.runAs(tenantA) {
            invoiceService.summarise(customer, currentPeriod)
        }

        val lineSum = summary.lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.amount.amount) }
        assertEquals(0, summary.totalAmount.amount.compareTo(lineSum))
    }

    @Test
    fun `groups charges by transaction code`() {
        repeat(2) { ingest(code = "VEHICLE_REGISTRATION") }
        ingest(code = "TITLE_TRANSFER")
        drain()

        val summary = TenantContext.runAs(tenantA) {
            invoiceService.summarise(customer, currentPeriod)
        }

        assertEquals(2, summary.lines.size)
        val vehicle = assertNotNull(summary.lines.firstOrNull { it.transactionCode == "VEHICLE_REGISTRATION" })
        assertEquals(2, vehicle.transactionCount)
        assertEquals("10.0000", vehicle.amount.amount.toPlainString())
    }

    @Test
    fun `an empty period summarises to zero`() {
        val summary = TenantContext.runAs(tenantA) {
            invoiceService.summarise(customer, currentPeriod)
        }

        assertTrue(summary.totalAmount.isZero())
        assertEquals(0, summary.transactionCount)
    }

    @Test
    fun `one customer's usage never appears on another's invoice`() {
        ingest(customerId = "customer-42")
        ingest(customerId = "customer-99")
        drain()

        val summary = TenantContext.runAs(tenantA) {
            invoiceService.summarise(CustomerId("customer-42"), currentPeriod)
        }

        assertEquals(1, summary.transactionCount)
    }

    // --- closing -----------------------------------------------------------

    @Test
    fun `closing freezes the totals into an invoice and its lines`() {
        repeat(3) { ingest() }
        drain()

        val closed = TenantContext.runAs(tenantA) {
            invoiceService.closePeriod(customer, currentPeriod)
        }

        assertEquals(InvoiceStatus.CLOSED, closed.status)
        assertEquals("15.0000", closed.totalAmount.amount.toPlainString())

        TenantContext.runAs(tenantA) {
            val rows = jdbc.queryForList("SELECT amount, transaction_count FROM invoice_line")
            assertEquals(1, rows.size)
            assertEquals(0, (rows[0]["amount"] as BigDecimal).compareTo(BigDecimal("15.0000")))
        }
    }

    @Test
    fun `a closed invoice reads back exactly as it was frozen`() {
        repeat(2) { ingest() }
        drain()
        TenantContext.runAs(tenantA) { invoiceService.closePeriod(customer, currentPeriod) }

        val reread = TenantContext.runAs(tenantA) { invoiceService.summarise(customer, currentPeriod) }

        // Read back from the frozen figures, not re-aggregated: re-aggregating could
        // report a different number than the one already billed.
        assertEquals(InvoiceStatus.CLOSED, reread.status)
        assertEquals("10.0000", reread.totalAmount.amount.toPlainString())
    }

    @Test
    fun `closing twice is refused`() {
        ingest()
        drain()
        TenantContext.runAs(tenantA) { invoiceService.closePeriod(customer, currentPeriod) }

        assertFailsWith<IllegalStateException> {
            TenantContext.runAs(tenantA) { invoiceService.closePeriod(customer, currentPeriod) }
        }
    }

    @Test
    fun `an empty period can still be closed`() {
        val closed = TenantContext.runAs(tenantA) {
            invoiceService.closePeriod(customer, currentPeriod)
        }

        assertEquals(InvoiceStatus.CLOSED, closed.status)
        assertTrue(closed.totalAmount.isZero())
    }

    // --- late arrival ------------------------------------------------------

    @Test
    fun `usage arriving after close is billed as an adjustment in the open period`() {
        // The late-arrival policy end to end. The closed invoice must not move, and the
        // late charge must land in the open period while still naming its origin.
        val previousPeriod = currentPeriod.previous()
        ingest(occurredAt = previousPeriod.start.plusSeconds(3600))
        drain()

        val closed = TenantContext.runAs(tenantA) {
            invoiceService.closePeriod(customer, previousPeriod)
        }
        assertEquals("5.0000", closed.totalAmount.amount.toPlainString())

        // A second event for the now-closed period turns up.
        ingest(occurredAt = previousPeriod.start.plusSeconds(7200))
        drain()

        TenantContext.runAs(tenantA) {
            // The closed invoice is untouched.
            val reread = invoiceService.summarise(customer, previousPeriod)
            assertEquals("5.0000", reread.totalAmount.amount.toPlainString())

            // And the late charge appears in the open period, as an adjustment that
            // still says which period the usage belongs to.
            val open = invoiceService.summarise(customer, currentPeriod)
            assertEquals("5.0000", open.totalAmount.amount.toPlainString())
            assertTrue(open.currentPeriodAmount.isZero())
            assertEquals("5.0000", open.adjustmentAmount.amount.toPlainString())

            val adjustment = assertNotNull(open.lines.firstOrNull { it.isAdjustment })
            assertEquals(previousPeriod, adjustment.originPeriod)
        }
    }

    @Test
    fun `usage arriving after the CURRENT period is closed still gets billed`() {
        // The scenario that exposed two bugs when run end to end: closing the period
        // that is still in progress. The adjustment was assigned to that same closed
        // period, the database CHECK rejected the insert, and the worker reported the
        // message DONE -- an accepted event silently never billed.
        ingest()
        drain()
        TenantContext.runAs(tenantA) { invoiceService.closePeriod(customer, currentPeriod) }

        ingest(quantity = "4")
        drain()

        TenantContext.runAs(tenantA) {
            // The closed invoice is untouched.
            assertEquals("5.0000", invoiceService.summarise(customer, currentPeriod).totalAmount.amount.toPlainString())

            // And the late charge landed in the next open period rather than vanishing.
            val next = invoiceService.summarise(customer, currentPeriod.next())
            assertEquals("10.0000", next.totalAmount.amount.toPlainString())
            assertEquals("10.0000", next.adjustmentAmount.amount.toPlainString())
            assertEquals(currentPeriod, next.lines.single().originPeriod)

            // Nothing was lost: every accepted event is accounted for.
            val report = reconciliationService.report(customer, currentPeriod)
            assertTrue(report.isBalanced, report.imbalanceDescription() ?: "")
        }
    }

    // --- reconciliation ----------------------------------------------------

    @Test
    fun `the reconciliation report balances after a mixed workload`() {
        // The identity that makes the report worth reading:
        //   received = accepted + duplicates + rejected
        // Exercised with every kind of delivery at once.
        val accepted = ingest()
        repeat(2) { ingest() }

        // A duplicate: same event id, different body.
        val original = assertIsAccepted(accepted)
        TenantContext.runAs(tenantA) {
            jdbc.queryForList(
                "SELECT event_id FROM raw_event WHERE id = ?", original.rawEventId,
            ).first()["event_id"].toString()
        }.let { existingEventId ->
            TenantContext.runAs(tenantA) {
                ingestionService.ingest(
                    RawTransactionInput(
                        eventId = existingEventId,
                        tenantId = tenantA.value,
                        customerId = customer.value,
                        transactionCode = "VEHICLE_REGISTRATION",
                        occurredAt = currentPeriod.start.plusSeconds(3600).toString(),
                        quantity = BigDecimal("2"),
                        rawPayload = """{"changed":true}""",
                        payloadHash = "a-different-hash",
                    )
                )
            }
        }

        // A rejection.
        TenantContext.runAs(tenantA) {
            ingestionService.ingest(
                RawTransactionInput(
                    eventId = UUID.randomUUID().toString(),
                    tenantId = tenantA.value,
                    customerId = customer.value,
                    transactionCode = "VEHICLE_REGISTRATION",
                    occurredAt = currentPeriod.start.plusSeconds(3600).toString(),
                    quantity = BigDecimal("-1"),           // invalid
                    rawPayload = "{}",
                    payloadHash = "rejected",
                )
            )
        }

        drain()

        val report = TenantContext.runAs(tenantA) {
            reconciliationService.report(customer, currentPeriod)
        }

        assertTrue(report.isBalanced, report.imbalanceDescription() ?: "")
        assertEquals(3, report.countOf(EventState.RATED))
        assertEquals(1, report.countOf(EventState.DUPLICATE))
        assertEquals(1, report.countOf(EventState.REJECTED))
        assertEquals(5, report.receivedCount)
    }

    @Test
    fun `rated transactions become invoiced once the period closes`() {
        repeat(2) { ingest() }
        drain()

        TenantContext.runAs(tenantA) {
            assertEquals(2, reconciliationService.report(customer, currentPeriod).countOf(EventState.RATED))

            invoiceService.closePeriod(customer, currentPeriod)

            val after = reconciliationService.report(customer, currentPeriod)
            assertEquals(0, after.countOf(EventState.RATED))
            assertEquals(2, after.countOf(EventState.INVOICED))
            assertTrue(after.isBalanced)
        }
    }

    @Test
    fun `every billed amount traces back to its event and pricing rule`() {
        // Requirement 6, end to end: a reviewer follows a total down to the individual
        // events and the rule that priced them, and re-derives the amount by hand.
        repeat(2) { ingest() }
        drain()

        TenantContext.runAs(tenantA) {
            val summary = invoiceService.summarise(customer, currentPeriod)
            val lines = reconciliationService.lines(customer, currentPeriod, "VEHICLE_REGISTRATION")

            assertEquals(2, lines.size)

            lines.forEach { line ->
                // Each row re-derives its own amount.
                assertEquals(
                    0,
                    line.quantity.multiply(line.unitPrice).compareTo(line.amount.amount),
                )
                // And names the rule that supplied the price.
                assertTrue(line.pricingRuleId > 0)
                assertTrue(line.eventId.isNotBlank())
            }

            // And they add up to the summary total.
            val traced = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.amount.amount) }
            assertEquals(0, summary.totalAmount.amount.compareTo(traced))
        }
    }

    @Test
    fun `an identical re-delivery is counted, not silently invisible`() {
        // The gap this closes: an identical duplicate writes nothing -- the unique
        // constraint rejects it, which is correct for billing -- so without a tally on
        // the original event it would never appear in the report the brief requires.
        val first = assertIsAccepted(ingest())
        val eventId = TenantContext.runAs(tenantA) {
            jdbc.queryForList("SELECT event_id FROM raw_event WHERE id = ?", first.rawEventId)
                .first()["event_id"].toString()
        }

        // Three byte-identical re-deliveries, as a retry storm would produce.
        repeat(3) {
            TenantContext.runAs(tenantA) {
                ingestionService.ingest(
                    RawTransactionInput(
                        eventId = eventId,
                        tenantId = tenantA.value,
                        customerId = customer.value,
                        transactionCode = "VEHICLE_REGISTRATION",
                        occurredAt = currentPeriod.start.plusSeconds(3600).toString(),
                        quantity = BigDecimal("2"),
                        rawPayload = """{"eventId":"$eventId"}""",
                        payloadHash = eventId,
                    )
                )
            }
        }
        drain()

        val report = TenantContext.runAs(tenantA) {
            reconciliationService.report(customer, currentPeriod)
        }

        // Each re-delivery is counted, not collapsed into one.
        assertEquals(3, report.countOf(EventState.DUPLICATE))
        assertEquals(1, report.countOf(EventState.RATED))
        assertEquals(4, report.receivedCount)
        assertTrue(report.isBalanced, report.imbalanceDescription() ?: "")
    }

    @Test
    fun `an unrated event is visible in the report rather than silently missing`() {
        ingest(code = "NO_SUCH_CODE")
        drain()

        val report = TenantContext.runAs(tenantA) {
            reconciliationService.report(customer, currentPeriod)
        }

        assertEquals(1, report.countOf(EventState.UNRATED))
        assertTrue(report.isBalanced)
    }

    @Test
    fun `reconciliation never crosses tenants`() {
        ingest(tenant = tenantA)
        ingest(tenant = tenantB)
        drain()

        val report = TenantContext.runAs(tenantB) {
            reconciliationService.report(customer, currentPeriod)
        }

        // tenant-b has exactly one event of its own and cannot see tenant-a's.
        assertEquals(1, report.receivedCount)
        assertEquals(1, report.countOf(EventState.RATED))
    }

    private fun assertIsAccepted(result: IngestionResult): IngestionResult.Accepted =
        result as IngestionResult.Accepted
}
