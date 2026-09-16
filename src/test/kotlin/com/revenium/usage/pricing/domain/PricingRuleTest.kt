package com.revenium.usage.pricing.domain

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PricingRuleTest {

    private fun rule(from: String, to: String? = null) = PricingRule(
        tenantId = "tenant-a",
        transactionCode = "CODE",
        unitPrice = BigDecimal("2.000000"),
        currency = "USD",
        effectiveFrom = Instant.parse(from),
        effectiveTo = to?.let(Instant::parse),
    )

    @Test
    fun `applies at the exact start instant`() {
        // Start is INCLUSIVE.
        val r = rule(from = "2026-07-01T00:00:00Z")
        assertTrue(r.appliesAt(Instant.parse("2026-07-01T00:00:00Z")))
    }

    @Test
    fun `does not apply one instant before the start`() {
        val r = rule(from = "2026-07-01T00:00:00Z")
        assertFalse(r.appliesAt(Instant.parse("2026-06-30T23:59:59.999Z")))
    }

    @Test
    fun `does not apply at the exact end instant`() {
        // End is EXCLUSIVE. This is the boundary that decides which of two adjacent
        // rules prices an event at the changeover -- and where cent-level bugs come from.
        val r = rule(from = "2026-01-01T00:00:00Z", to = "2026-07-01T00:00:00Z")
        assertFalse(r.appliesAt(Instant.parse("2026-07-01T00:00:00Z")))
    }

    @Test
    fun `applies one instant before the end`() {
        val r = rule(from = "2026-01-01T00:00:00Z", to = "2026-07-01T00:00:00Z")
        assertTrue(r.appliesAt(Instant.parse("2026-06-30T23:59:59.999Z")))
    }

    @Test
    fun `an open-ended rule applies indefinitely`() {
        val r = rule(from = "2026-01-01T00:00:00Z", to = null)
        assertTrue(r.appliesAt(Instant.parse("2099-12-31T23:59:59Z")))
    }

    @Test
    fun `adjacent rules cover every instant exactly once`() {
        // The property the EXCLUDE constraint enforces in the database, verified here in
        // the domain: no gap where an event would be unrated, no overlap where two rules
        // would both claim it.
        val first = rule(from = "2026-01-01T00:00:00Z", to = "2026-07-01T00:00:00Z")
        val second = rule(from = "2026-07-01T00:00:00Z")

        listOf(
            "2026-06-30T23:59:59.999Z" to true,
            "2026-07-01T00:00:00Z" to false,
        ).forEach { (instant, expectedFirst) ->
            val at = Instant.parse(instant)
            assertEquals(expectedFirst, first.appliesAt(at))
            assertEquals(!expectedFirst, second.appliesAt(at))
        }
    }

    @Test
    fun `exposes the currency as a Currency instance`() {
        assertEquals("USD", rule(from = "2026-01-01T00:00:00Z").currencyAsCurrency().currencyCode)
    }
}
