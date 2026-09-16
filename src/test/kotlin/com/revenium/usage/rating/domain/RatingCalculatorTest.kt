package com.revenium.usage.rating.domain

import com.revenium.usage.pricing.domain.PricingRule
import com.revenium.usage.pricing.domain.PricingRuleLookup
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RatingCalculatorTest {

    private val tenant = TenantId("tenant-a")
    private val code = TransactionCode("VEHICLE_REGISTRATION")
    private val now = Instant.parse("2026-09-16T12:00:00Z")

    private fun rule(
        unitPrice: String = "2.000000",
        from: String = "2026-01-01T00:00:00Z",
        to: String? = null,
        currency: String = "USD",
        id: Long = 1L,
    ) = PricingRule(
        tenantId = tenant.value,
        transactionCode = code.value,
        unitPrice = BigDecimal(unitPrice),
        currency = currency,
        effectiveFrom = Instant.parse(from),
        effectiveTo = to?.let(Instant::parse),
        id = id,
    )

    /** A lookup that applies the same half-open rule the database constraint enforces. */
    private fun lookupOf(vararg rules: PricingRule) = object : PricingRuleLookup {
        override fun findApplicable(tenant: TenantId, code: TransactionCode, occurredAt: Instant) =
            rules.firstOrNull { it.appliesAt(occurredAt) }
    }

    private fun calculator(
        vararg rules: PricingRule,
        cutoff: Duration = Duration.ofDays(90),
    ) = RatingCalculator(lookupOf(*rules), cutoff)

    private fun request(
        quantity: String = "2",
        occurredAt: String = "2026-08-15T14:22:31Z",
        receivedAt: String = "2026-08-15T14:25:00Z",
    ) = RatingRequest(
        tenant = tenant,
        transactionCode = code,
        quantity = Quantity.of(BigDecimal(quantity)),
        occurredAt = Instant.parse(occurredAt),
        receivedAt = Instant.parse(receivedAt),
    )

    private val nothingClosed: (BillingPeriod) -> Boolean = { false }

    // --- the happy path ----------------------------------------------------

    @Test
    fun `prices an event at the applicable rule`() {
        val outcome = calculator(rule()).rate(request(), nothingClosed, now)

        val rated = assertIs<RatingOutcome.Rated>(outcome)
        assertEquals("4.0000", rated.amount.amount.toPlainString())
        assertEquals(1L, rated.rule.id)
        assertFalse(rated.isLateAdjustment)
    }

    @Test
    fun `charges an in-period event to its own period`() {
        val outcome = calculator(rule()).rate(request(), nothingClosed, now)

        val rated = assertIs<RatingOutcome.Rated>(outcome)
        assertEquals(BillingPeriod(YearMonth.of(2026, 8)), rated.billingPeriod)
        assertEquals(rated.originPeriod, rated.billingPeriod)
    }

    // --- effective dating boundaries ---------------------------------------

    @Test
    fun `a rule applies at the exact instant it becomes effective`() {
        // Start is INCLUSIVE. An event at precisely 00:00:00Z on the changeover day is
        // priced by the new rule, not left unrated.
        val outcome = calculator(rule(from = "2026-08-01T00:00:00Z"))
            .rate(request(occurredAt = "2026-08-01T00:00:00Z"), nothingClosed, now)

        assertIs<RatingOutcome.Rated>(outcome)
    }

    @Test
    fun `a rule does not apply at the exact instant it expires`() {
        // End is EXCLUSIVE. This is the boundary where off-by-one-cent bugs live: with
        // an inclusive end, the changeover instant would match two rules at once.
        val outcome = calculator(rule(to = "2026-08-01T00:00:00Z"))
            .rate(request(occurredAt = "2026-08-01T00:00:00Z"), nothingClosed, now)

        assertIs<RatingOutcome.Unrated>(outcome)
    }

    @Test
    fun `adjacent rules hand over cleanly at the changeover instant`() {
        // The seeded scenario: 2.00 in H1, 2.50 from H2. Adjacent, no gap, no overlap.
        val calculator = calculator(
            rule(unitPrice = "2.000000", from = "2026-01-01T00:00:00Z", to = "2026-07-01T00:00:00Z", id = 1L),
            rule(unitPrice = "2.500000", from = "2026-07-01T00:00:00Z", id = 2L),
        )

        val before = calculator.rate(request(occurredAt = "2026-06-30T23:59:59.999Z"), nothingClosed, now)
        val after = calculator.rate(request(occurredAt = "2026-07-01T00:00:00Z"), nothingClosed, now)

        assertEquals(1L, assertIs<RatingOutcome.Rated>(before).rule.id)
        assertEquals("4.0000", assertIs<RatingOutcome.Rated>(before).amount.amount.toPlainString())
        assertEquals(2L, assertIs<RatingOutcome.Rated>(after).rule.id)
        assertEquals("5.0000", assertIs<RatingOutcome.Rated>(after).amount.amount.toPlainString())
    }

    @Test
    fun `an open-ended rule applies indefinitely`() {
        val outcome = calculator(rule(to = null)).rate(
            request(occurredAt = "2099-01-01T00:00:00Z", receivedAt = "2099-01-01T00:05:00Z"),
            nothingClosed,
            Instant.parse("2099-01-02T00:00:00Z"),
        )

        assertIs<RatingOutcome.Rated>(outcome)
    }

    @Test
    fun `an event before any rule is unrated`() {
        val outcome = calculator(rule(from = "2026-06-01T00:00:00Z"))
            .rate(request(occurredAt = "2026-05-31T23:59:59Z"), nothingClosed, now)

        assertIs<RatingOutcome.Unrated>(outcome)
    }

    @Test
    fun `prices a reprocessed event at the rule in effect when it occurred`() {
        // The property that makes replay safe: rating in October an event from June
        // must produce June's price, or reconciliation between the original and the
        // replay becomes meaningless.
        val calculator = calculator(
            rule(unitPrice = "2.000000", from = "2026-01-01T00:00:00Z", to = "2026-07-01T00:00:00Z", id = 1L),
            rule(unitPrice = "2.500000", from = "2026-07-01T00:00:00Z", id = 2L),
        )

        val outcome = calculator.rate(
            // Received promptly back in June; only the reprocessing happens in October.
            request(occurredAt = "2026-06-15T00:00:00Z", receivedAt = "2026-06-15T00:05:00Z"),
            nothingClosed,
            now = Instant.parse("2026-10-01T00:00:00Z"),
        )

        assertEquals("4.0000", assertIs<RatingOutcome.Rated>(outcome).amount.amount.toPlainString())
    }

    // --- rounding ----------------------------------------------------------

    @Test
    fun `multiplies at full precision and rounds once`() {
        // 3 x 0.003333 = 0.009999 exactly, which rounds to 0.0100. Rounding the price
        // first would give 0.0099 -- wrong, and wrong in a way that compounds.
        val outcome = calculator(rule(unitPrice = "0.003333"))
            .rate(request(quantity = "3"), nothingClosed, now)

        assertEquals("0.0100", assertIs<RatingOutcome.Rated>(outcome).amount.amount.toPlainString())
    }

    @Test
    fun `handles a fractional quantity`() {
        val outcome = calculator(rule(unitPrice = "2.000000"))
            .rate(request(quantity = "2.5"), nothingClosed, now)

        assertEquals("5.0000", assertIs<RatingOutcome.Rated>(outcome).amount.amount.toPlainString())
    }

    @Test
    fun `a zero unit price produces a zero amount, not an error`() {
        // A genuinely free transaction code still yields a traceable rated row.
        val outcome = calculator(rule(unitPrice = "0.000000"))
            .rate(request(), nothingClosed, now)

        assertTrue(assertIs<RatingOutcome.Rated>(outcome).amount.isZero())
    }

    @Test
    fun `carries the rule's currency onto the amount`() {
        val outcome = calculator(rule(currency = "EUR")).rate(request(), nothingClosed, now)

        assertEquals("EUR", assertIs<RatingOutcome.Rated>(outcome).amount.currency.currencyCode)
    }

    // --- late arrival ------------------------------------------------------

    @Test
    fun `charges a late event as an adjustment in the open period`() {
        // The policy: a closed invoice is never reopened, so the charge lands in the
        // period that is currently open, flagged as an adjustment.
        val augustClosed: (BillingPeriod) -> Boolean = { it == BillingPeriod(YearMonth.of(2026, 8)) }

        val outcome = calculator(rule()).rate(request(), augustClosed, now)

        val rated = assertIs<RatingOutcome.Rated>(outcome)
        assertTrue(rated.isLateAdjustment)
        assertEquals(BillingPeriod(YearMonth.of(2026, 8)), rated.originPeriod)
        assertEquals(BillingPeriod(YearMonth.of(2026, 9)), rated.billingPeriod)
    }

    @Test
    fun `a late event still rates at its original period's price`() {
        // Late does not mean repriced. The customer is charged what the usage cost when
        // it happened, only in a later invoice.
        val augustClosed: (BillingPeriod) -> Boolean = { it == BillingPeriod(YearMonth.of(2026, 8)) }
        val calculator = calculator(
            rule(unitPrice = "2.000000", from = "2026-01-01T00:00:00Z", to = "2026-09-01T00:00:00Z", id = 1L),
            rule(unitPrice = "9.000000", from = "2026-09-01T00:00:00Z", id = 2L),
        )

        val outcome = calculator.rate(request(), augustClosed, now)

        val rated = assertIs<RatingOutcome.Rated>(outcome)
        assertEquals("4.0000", rated.amount.amount.toPlainString())
        assertEquals(1L, rated.rule.id)
    }

    @Test
    fun `a late adjustment skips a closed current period`() {
        // Found end to end: an operator closed the period that was still in progress, so
        // "the period containing now" was itself closed. The adjustment was assigned to
        // the same period as its origin, which both contradicts the policy and violates
        // the database CHECK that a late adjustment's two periods must differ -- the
        // insert was rejected and the charge vanished.
        val augustAndSeptemberClosed: (BillingPeriod) -> Boolean = {
            it == BillingPeriod(YearMonth.of(2026, 8)) || it == BillingPeriod(YearMonth.of(2026, 9))
        }

        val outcome = calculator(rule()).rate(request(), augustAndSeptemberClosed, now)

        val rated = assertIs<RatingOutcome.Rated>(outcome)
        assertEquals(BillingPeriod(YearMonth.of(2026, 8)), rated.originPeriod)
        assertEquals(BillingPeriod(YearMonth.of(2026, 10)), rated.billingPeriod)
        assertTrue(rated.isLateAdjustment)
    }

    @Test
    fun `a late adjustment's periods always differ`() {
        // The invariant the database CHECK enforces, asserted here so a regression fails
        // in a unit test rather than as a rejected insert in production.
        val everythingClosedUntilDecember: (BillingPeriod) -> Boolean = {
            it < BillingPeriod(YearMonth.of(2026, 12))
        }

        val rated = assertIs<RatingOutcome.Rated>(
            calculator(rule()).rate(request(), everythingClosedUntilDecember, now)
        )

        assertTrue(rated.isLateAdjustment)
        assertTrue(rated.billingPeriod != rated.originPeriod)
        assertEquals(BillingPeriod(YearMonth.of(2026, 12)), rated.billingPeriod)
    }

    @Test
    fun `a late adjustment lands in the current period, not the one after its own`() {
        // A January event arriving in September must not be charged to February, which
        // closed months ago -- it goes to the period that is actually open now.
        val onlySeptemberOpen: (BillingPeriod) -> Boolean = { it != BillingPeriod(YearMonth.of(2026, 9)) }

        val outcome = calculator(rule())
            .rate(request(occurredAt = "2026-08-20T00:00:00Z"), onlySeptemberOpen, now)

        assertEquals(
            BillingPeriod(YearMonth.of(2026, 9)),
            assertIs<RatingOutcome.Rated>(outcome).billingPeriod,
        )
    }

    // --- quarantine --------------------------------------------------------

    @Test
    fun `quarantines an event far beyond the cutoff`() {
        val outcome = calculator(rule(from = "2019-01-01T00:00:00Z")).rate(
            // Occurred in 2019 and only turned up now: an accidental replay.
            request(occurredAt = "2019-06-01T00:00:00Z", receivedAt = now.toString()),
            nothingClosed,
            now,
        )

        val quarantined = assertIs<RatingOutcome.Quarantined>(outcome)
        assertTrue(quarantined.reason.contains("cutoff"))
    }

    @Test
    fun `an event exactly at the cutoff is still billed`() {
        // Boundary: the cutoff excludes what is *beyond* it, so the last acceptable
        // instant must still rate.
        val cutoff = Duration.ofDays(90)
        val occurredAt = now.minus(cutoff)

        val outcome = calculator(rule(from = "2026-01-01T00:00:00Z"), cutoff = cutoff).rate(
            request(occurredAt = occurredAt.toString(), receivedAt = now.toString()),
            nothingClosed,
            now,
        )

        assertIs<RatingOutcome.Rated>(outcome)
    }

    @Test
    fun `an event one second past the cutoff is quarantined`() {
        val cutoff = Duration.ofDays(90)
        val occurredAt = now.minus(cutoff).minusSeconds(1)

        val outcome = calculator(rule(from = "2026-01-01T00:00:00Z"), cutoff = cutoff).rate(
            request(occurredAt = occurredAt.toString(), receivedAt = now.toString()),
            nothingClosed,
            now,
        )

        assertIs<RatingOutcome.Quarantined>(outcome)
    }

    @Test
    fun `a missing rule takes precedence over the cutoff`() {
        // Without a rule there is no price to quarantine. Reporting UNRATED is the more
        // actionable answer: it names a fixable gap in configuration.
        val outcome = calculator().rate(
            request(occurredAt = "2019-01-01T00:00:00Z", receivedAt = now.toString()),
            nothingClosed,
            now,
        )

        assertIs<RatingOutcome.Unrated>(outcome)
    }
}
