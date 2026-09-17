package com.revenium.usage.pricing.infrastructure.persistence

import com.revenium.usage.pricing.domain.model.PricingRule
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.shared.domain.UnitPrice
import com.revenium.usage.tenancy.TenantId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PricingRuleEntityTest {

    private fun rule(to: String? = null) = PricingRule(
        tenantId = TenantId("tenant-a"),
        transactionCode = TransactionCode("VEHICLE_REGISTRATION"),
        unitPrice = UnitPrice(BigDecimal("2.500000")),
        currency = Currency.getInstance("USD"),
        effectiveFrom = Instant.parse("2026-07-01T00:00:00Z"),
        effectiveTo = to?.let(Instant::parse),
        description = "Rate increase from H2 2026",
        createdAt = Instant.parse("2026-06-30T00:00:00Z"),
        id = 7L,
    )

    @Test
    fun `an open-ended rule round trips`() {
        val original = rule()

        val mapped = PricingRuleEntity.fromDomain(original).toDomain()

        assertEquals(original, mapped)
        assertNull(mapped.effectiveTo)
    }

    @Test
    fun `a closed rule round trips with both bounds`() {
        val original = rule(to = "2027-01-01T00:00:00Z")

        assertEquals(original, PricingRuleEntity.fromDomain(original).toDomain())
    }

    @Test
    fun `the unit price survives at full precision and is never rounded`() {
        // Rounding a unit price would compound the error across every transaction that
        // uses it. Only amounts round, and only once.
        val mapped = PricingRuleEntity.fromDomain(rule()).toDomain()

        assertEquals("2.500000", mapped.unitPrice.value.toPlainString())
    }

    @Test
    fun `the round trip keeps the half-open validity window intact`() {
        // The boundary that decides which of two adjacent rules prices an event, and
        // therefore where cent-level bugs come from.
        val mapped = PricingRuleEntity.fromDomain(rule(to = "2027-01-01T00:00:00Z")).toDomain()

        assertTrue(mapped.appliesAt(Instant.parse("2026-12-31T23:59:59.999Z")))
        assertTrue(!mapped.appliesAt(Instant.parse("2027-01-01T00:00:00Z")))
    }
}
