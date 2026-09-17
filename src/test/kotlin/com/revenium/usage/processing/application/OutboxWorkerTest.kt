package com.revenium.usage.processing.application

import com.revenium.usage.pricing.domain.model.PricingRule
import com.revenium.usage.processing.domain.model.InstanceId
import com.revenium.usage.processing.infrastructure.ClaimedWork
import com.revenium.usage.processing.infrastructure.OutboxClaimRepository
import com.revenium.usage.rating.application.ConcurrentRatingException
import com.revenium.usage.rating.application.RatingService
import com.revenium.usage.rating.domain.model.RatingOutcome
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.UnitPrice
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantContext
import com.revenium.usage.tenancy.TenantId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Currency
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutboxWorkerTest {

    private val now = Instant.parse("2026-09-16T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val claims = mockk<OutboxClaimRepository>(relaxed = true)
    private val ratingService = mockk<RatingService>()
    private val status = mockk<OutboxStatusRecorder>(relaxed = true)

    private val worker = OutboxWorker(
        claims = claims,
        ratingService = ratingService,
        status = status,
        properties = OutboxProperties(),
        clock = clock,
        instanceId = InstanceId("test-instance"),
    )

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    private fun work(tenantId: String = "tenant-a", rawEventId: Long = 1L) = ClaimedWork(
        outboxId = rawEventId,
        tenantId = tenantId,
        rawEventId = rawEventId,
        customerId = "customer-42",
        transactionCode = "VEHICLE_REGISTRATION",
        occurredAt = Instant.parse("2026-08-15T14:22:31Z"),
        receivedAt = Instant.parse("2026-08-15T14:25:00Z"),
        quantity = BigDecimal("2"),
        attemptCount = 0,
    )

    private fun ratedOutcome() = RatingOutcome.Rated(
        rule = PricingRule(
            tenantId = TenantId("tenant-a"),
            transactionCode = TransactionCode("VEHICLE_REGISTRATION"),
            unitPrice = UnitPrice(BigDecimal("2.000000")),
            currency = Currency.getInstance("USD"),
            effectiveFrom = Instant.parse("2026-01-01T00:00:00Z"),
            id = 1L,
        ),
        amount = Money.of(BigDecimal("4"), Currency.getInstance("USD")),
        billingPeriod = BillingPeriod(YearMonth.of(2026, 8)),
        originPeriod = BillingPeriod(YearMonth.of(2026, 8)),
        isLateAdjustment = false,
    )

    // --- polling -----------------------------------------------------------

    @Test
    fun `does nothing when the queue is empty`() {
        every { claims.claimBatch(any(), any(), any()) } returns emptyList()

        assertEquals(0, worker.pollOnce())
        verify(exactly = 0) { ratingService.rate(any()) }
    }

    @Test
    fun `processes every message in the claimed batch`() {
        every { claims.claimBatch(any(), any(), any()) } returns listOf(work(rawEventId = 1L), work(rawEventId = 2L))
        every { ratingService.rate(any()) } returns ratedOutcome()

        assertEquals(2, worker.pollOnce())
        verify(exactly = 2) { ratingService.rate(any()) }
    }

    @Test
    fun `returns abandoned claims to the queue before claiming more`() {
        // A worker killed mid-batch leaves rows in PROCESSING that the claim query does
        // not look at. Without this they would sit there for ever.
        every { claims.claimBatch(any(), any(), any()) } returns emptyList()

        worker.pollOnce()

        verify { claims.reclaimStale(now, OutboxProperties().staleClaimTimeout) }
    }

    // --- tenant propagation ------------------------------------------------

    @Test
    fun `rates each message under the tenant on its own row`() {
        // The claim runs unscoped across tenants, so the scope must come from the row --
        // never inherited from whatever this pool thread did previously.
        val observed = mutableListOf<TenantId?>()
        every { claims.claimBatch(any(), any(), any()) } returns
            listOf(work(tenantId = "tenant-a", rawEventId = 1L), work(tenantId = "tenant-b", rawEventId = 2L))
        every { ratingService.rate(any()) } answers {
            observed += TenantContext.currentOrNull()
            ratedOutcome()
        }

        worker.pollOnce()

        assertEquals(listOf<TenantId?>(TenantId("tenant-a"), TenantId("tenant-b")), observed)
    }

    @Test
    fun `leaves no tenant in scope after the cycle`() {
        every { claims.claimBatch(any(), any(), any()) } returns listOf(work())
        every { ratingService.rate(any()) } returns ratedOutcome()

        worker.pollOnce()

        assertNull(TenantContext.currentOrNull())
    }

    // --- outcome handling --------------------------------------------------

    @Test
    fun `marks a rated message done`() {
        every { claims.claimBatch(any(), any(), any()) } returns listOf(work())
        every { ratingService.rate(any()) } returns ratedOutcome()

        worker.pollOnce()

        verify(exactly = 1) { status.markDone(any()) }
    }

    @Test
    fun `marks an already-rated message done rather than failed`() {
        // At-least-once delivery makes this a normal outcome, not an error.
        every { claims.claimBatch(any(), any(), any()) } returns listOf(work())
        every { ratingService.rate(any()) } returns RatingOutcome.AlreadyRated(99L)

        worker.pollOnce()

        verify(exactly = 1) { status.markDone(any()) }
        verify(exactly = 0) { status.markFailed(any(), any()) }
    }

    @Test
    fun `a missing pricing rule is unrated, not a failure`() {
        // The distinction that keeps real usage from being dead-lettered: a rule may be
        // created tomorrow, and the event must still be waiting when it is.
        every { claims.claimBatch(any(), any(), any()) } returns listOf(work())
        every { ratingService.rate(any()) } returns RatingOutcome.Unrated("no rule")

        worker.pollOnce()

        verify(exactly = 1) { status.markUnrated(any()) }
        verify(exactly = 0) { status.markFailed(any(), any()) }
    }

    @Test
    fun `quarantines an event flagged by the cutoff`() {
        every { claims.claimBatch(any(), any(), any()) } returns listOf(work())
        every { ratingService.rate(any()) } returns RatingOutcome.Quarantined("too old")

        worker.pollOnce()

        verify(exactly = 1) { status.markQuarantined(any(), "too old") }
    }

    @Test
    fun `losing a concurrent rating race counts as done, not as a failure`() {
        // The other worker committed the charge. Retrying would only fail again on the
        // same unique index, and counting it as an attempt would eventually dead-letter
        // an event that was rated correctly.
        every { claims.claimBatch(any(), any(), any()) } returns listOf(work())
        every { ratingService.rate(any()) } throws
            ConcurrentRatingException(1L, RuntimeException("duplicate key"))

        worker.pollOnce()

        verify(exactly = 1) { status.markDone(any()) }
        verify(exactly = 0) { status.markFailed(any(), any()) }
    }

    @Test
    fun `an unexpected failure is recorded for retry`() {
        every { claims.claimBatch(any(), any(), any()) } returns listOf(work())
        every { ratingService.rate(any()) } throws RuntimeException("database unreachable")

        worker.pollOnce()

        verify(exactly = 1) { status.markFailed(any(), any()) }
    }

    @Test
    fun `one failing message does not stop the rest of the batch`() {
        // Each message is rated in its own transaction precisely so a poison message
        // cannot stall the good ones queued behind it.
        every { claims.claimBatch(any(), any(), any()) } returns
            listOf(work(rawEventId = 1L), work(rawEventId = 2L), work(rawEventId = 3L))
        every { ratingService.rate(match { it.rawEventId == 2L }) } throws RuntimeException("boom")
        every { ratingService.rate(match { it.rawEventId != 2L }) } returns ratedOutcome()

        assertEquals(3, worker.pollOnce())

        verify(exactly = 2) { status.markDone(any()) }
        verify(exactly = 1) { status.markFailed(any(), any()) }
    }
}
