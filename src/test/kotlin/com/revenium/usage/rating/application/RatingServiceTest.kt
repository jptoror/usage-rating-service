package com.revenium.usage.rating.application

import com.revenium.usage.invoicing.domain.port.out.BillingPeriodStatusLookup
import com.revenium.usage.pricing.domain.model.PricingRule
import com.revenium.usage.pricing.domain.port.out.PricingRuleLookup
import com.revenium.usage.rating.domain.model.RateableTransaction
import com.revenium.usage.rating.domain.model.RatedTransaction
import com.revenium.usage.rating.domain.model.RatingCalculator
import com.revenium.usage.rating.domain.model.RatingOutcome
import com.revenium.usage.rating.domain.port.out.RatedTransactionStore
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.shared.domain.UnitPrice
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RatingServiceTest {

    private val now = Instant.parse("2026-09-16T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tenant = TenantId("tenant-a")

    private val rule = PricingRule(
        tenantId = TenantId("tenant-a"),
        transactionCode = TransactionCode("VEHICLE_REGISTRATION"),
        unitPrice = UnitPrice(BigDecimal("2.000000")),
        currency = Currency.getInstance("USD"),
        effectiveFrom = Instant.parse("2026-01-01T00:00:00Z"),
        id = 7L,
    )

    private val pricingRules = object : PricingRuleLookup {
        override fun findApplicable(tenant: TenantId, code: TransactionCode, occurredAt: Instant) =
            rule.takeIf { it.appliesAt(occurredAt) }

        override fun findAllFor(tenant: TenantId) = listOf(rule)

        override fun findById(tenant: TenantId, id: Long) = rule.takeIf { it.id == id }
    }

    private val ratedTransactions = mockk<RatedTransactionStore> {
        every { save(any()) } answers { firstArg() }
    }
    private val billingPeriods = mockk<BillingPeriodStatusLookup> {
        every { isClosed(any(), any(), any()) } returns false
    }

    private val service = RatingService(
        calculator = RatingCalculator(pricingRules, Duration.ofDays(90)),
        ratedTransactions = ratedTransactions,
        billingPeriods = billingPeriods,
        clock = clock,
    )

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    private fun work(tenantId: String = "tenant-a") = RateableTransaction(
        tenantId = TenantId(tenantId),
        rawEventId = 1L,
        customerId = CustomerId("customer-42"),
        transactionCode = TransactionCode("VEHICLE_REGISTRATION"),
        occurredAt = Instant.parse("2026-08-15T14:22:31Z"),
        receivedAt = Instant.parse("2026-08-15T14:25:00Z"),
        quantity = Quantity(BigDecimal("2")),
    )

    private fun notYetRated() = every { ratedTransactions.findCurrent(any(), any()) } returns null

    @Test
    fun `persists both the rule id and the price it had at the time`() {
        // The redundancy is the point: the row must keep explaining its own amount even
        // after the rule is corrected.
        notYetRated()
        val persisted = slot<RatedTransaction>()
        every { ratedTransactions.save(capture(persisted)) } answers { firstArg() }

        TenantContext.runAs(tenant) { service.rate(work()) }

        assertEquals(7L, persisted.captured.pricingRuleId)
        assertEquals(0, persisted.captured.unitPrice.value.compareTo(BigDecimal("2.000000")))
        assertEquals("4.0000", persisted.captured.amount.amount.toPlainString())
    }

    @Test
    fun `records both the origin period and the billing period`() {
        notYetRated()
        val persisted = slot<RatedTransaction>()
        every { ratedTransactions.save(capture(persisted)) } answers { firstArg() }

        TenantContext.runAs(tenant) { service.rate(work()) }

        assertEquals("2026-08", persisted.captured.originPeriod.toString())
        assertEquals("2026-08", persisted.captured.billingPeriod.toString())
        assertEquals(false, persisted.captured.isLateAdjustment)
    }

    @Test
    fun `bills a late event to the open period while keeping its origin`() {
        notYetRated()
        every { billingPeriods.isClosed(any(), any(), BillingPeriod.parse("2026-08")) } returns true
        val persisted = slot<RatedTransaction>()
        every { ratedTransactions.save(capture(persisted)) } answers { firstArg() }

        TenantContext.runAs(tenant) { service.rate(work()) }

        // The closed invoice is untouched; the charge lands in September, traceable back.
        assertEquals("2026-08", persisted.captured.originPeriod.toString())
        assertEquals("2026-09", persisted.captured.billingPeriod.toString())
        assertTrue(persisted.captured.isLateAdjustment)
    }

    @Test
    fun `skips work that is already rated`() {
        // At-least-once delivery means this happens routinely; it is not an error.
        every { ratedTransactions.findCurrent(tenant, 1L) } returns
            mockk<RatedTransaction> { every { id } returns 99L }

        val outcome = TenantContext.runAs(tenant) { service.rate(work()) }

        assertEquals(99L, assertIs<RatingOutcome.AlreadyRated>(outcome).ratedTransactionId)
        verify(exactly = 0) { ratedTransactions.save(any()) }
    }

    @Test
    fun `turns a lost concurrency race into a distinct signal`() {
        // Two workers passed the "already rated" check simultaneously and the database
        // refused the second charge. That is the unique index doing its job, and the
        // caller needs to tell it apart from a genuine failure.
        notYetRated()
        every { ratedTransactions.save(any()) } throws
            DataIntegrityViolationException("uq_rated_transaction_current")

        assertFailsWith<ConcurrentRatingException> {
            TenantContext.runAs(tenant) { service.rate(work()) }
        }
    }

    @Test
    fun `a CHECK violation is a real failure, not a lost race`() {
        // The bug this guards against: every DataIntegrityViolationException was reported
        // as a concurrent-rating race, so the worker marked the message DONE and the
        // charge vanished with nothing recording why. Only the unique index on the
        // current rating means someone else won.
        notYetRated()
        every { ratedTransactions.save(any()) } throws
            DataIntegrityViolationException("violates check constraint \"ck_rated_late_adjustment_consistent\"")

        // Propagated as-is, so the worker retries and eventually dead-letters it rather
        // than recording a charge that was never written.
        assertFailsWith<DataIntegrityViolationException> {
            TenantContext.runAs(tenant) { service.rate(work()) }
        }
    }

    @Test
    fun `persists nothing when no pricing rule applies`() {
        notYetRated()
        val outcome = TenantContext.runAs(tenant) {
            service.rate(work().copy(occurredAt = Instant.parse("2025-01-01T00:00:00Z")))
        }

        assertIs<RatingOutcome.Unrated>(outcome)
        verify(exactly = 0) { ratedTransactions.save(any()) }
    }

    @Test
    fun `refuses to rate work belonging to another tenant`() {
        // Defence in depth: the claim already scopes per row, but a mismatch here would
        // mean a bug that must not be allowed to write a charge.
        notYetRated()

        assertFailsWith<IllegalArgumentException> {
            TenantContext.runAs(tenant) { service.rate(work(tenantId = "tenant-b")) }
        }
    }
}
