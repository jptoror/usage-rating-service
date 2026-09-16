package com.revenium.usage.reconciliation.domain

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconciliationReportTest {

    private val usd: Currency = Currency.getInstance("USD")
    private val now = Instant.parse("2026-09-16T12:00:00Z")

    private fun report(
        received: Long,
        rejected: Long = 0,
        duplicates: Long = 0,
        accepted: Long = 0,
        unrated: Long = 0,
        rated: Long = 0,
        invoiced: Long = 0,
        failed: Long = 0,
        quarantined: Long = 0,
    ) = ReconciliationReport(
        customerId = CustomerId("customer-42"),
        period = BillingPeriod.parse("2026-08"),
        receivedCount = received,
        states = listOf(
            StateCount(EventState.REJECTED, rejected, null),
            StateCount(EventState.DUPLICATE, duplicates, null),
            StateCount(EventState.ACCEPTED, accepted, null),
            StateCount(EventState.UNRATED, unrated, null),
            StateCount(EventState.RATED, rated, Money.of(BigDecimal.TEN, usd)),
            StateCount(EventState.INVOICED, invoiced, Money.of(BigDecimal.TEN, usd)),
            StateCount(EventState.FAILED, failed, null),
            StateCount(EventState.QUARANTINED, quarantined, null),
        ),
        billedAmount = Money.of(BigDecimal("20"), usd),
        generatedAt = now,
    )

    @Test
    fun `balances when every received event is accounted for`() {
        // 100 received: 90 rated, 7 duplicates, 3 rejected.
        val r = report(received = 100, rated = 90, duplicates = 7, rejected = 3)

        assertTrue(r.isBalanced)
        assertNull(r.imbalanceDescription())
    }

    @Test
    fun `balances across every terminal state`() {
        // Each state must be counted exactly once, or an event would be double-counted
        // or vanish. This exercises all of them together.
        val r = report(
            received = 100,
            rejected = 5, duplicates = 5,
            accepted = 10, unrated = 20, rated = 30, invoiced = 25, failed = 3, quarantined = 2,
        )

        assertTrue(r.isBalanced)
        assertEquals(90, r.accepted)
    }

    @Test
    fun `reports an imbalance rather than plausible-looking numbers`() {
        // 100 arrived but only 89 can be accounted for: 11 events are unexplained, and
        // the report must say so instead of presenting a total that looks fine.
        val r = report(received = 100, rated = 89)

        assertFalse(r.isBalanced)
        val detail = assertNotNull(r.imbalanceDescription())
        assertTrue(detail.contains("100"))
        assertTrue(detail.contains("89"))
    }

    @Test
    fun `an empty period balances`() {
        val r = report(received = 0)

        assertTrue(r.isBalanced)
        assertEquals(0, r.accepted)
    }

    @Test
    fun `duplicates and rejections count as received but never as accepted`() {
        // A duplicate creates no new charge and a rejection never becomes usage, yet
        // both arrived and must be accounted for.
        val r = report(received = 10, duplicates = 4, rejected = 6)

        assertTrue(r.isBalanced)
        assertEquals(0, r.accepted)
        assertEquals(4, r.countOf(EventState.DUPLICATE))
        assertEquals(6, r.countOf(EventState.REJECTED))
    }

    @Test
    fun `unrated and quarantined events are accepted but not billed`() {
        // They are real usage the service holds rather than discards, so they must
        // appear on the accepted side even though no charge exists for them.
        val r = report(received = 10, unrated = 6, quarantined = 4)

        assertTrue(r.isBalanced)
        assertEquals(10, r.accepted)
        assertEquals(0, r.countOf(EventState.RATED))
    }

    @Test
    fun `countOf returns zero for a state with no events`() {
        assertEquals(0, report(received = 0).countOf(EventState.FAILED))
    }
}
