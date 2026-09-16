package com.revenium.usage.rating.domain

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RatedTransactionTest {

    private val now = Instant.parse("2026-09-16T12:00:00Z")

    private fun rated(
        id: Long = 1L,
        billingPeriod: String = "2026-08-01",
        originPeriod: String = "2026-08-01",
        isLate: Boolean = false,
    ) = RatedTransaction(
        tenantId = "tenant-a",
        rawEventId = 1L,
        customerId = "customer-42",
        transactionCode = "VEHICLE_REGISTRATION",
        pricingRuleId = 7L,
        unitPrice = BigDecimal("2.500000"),
        quantity = BigDecimal("2"),
        amount = BigDecimal("5.0000"),
        currency = "USD",
        occurredAt = now,
        billingPeriod = LocalDate.parse(billingPeriod),
        originPeriod = LocalDate.parse(originPeriod),
        isLateAdjustment = isLate,
        id = id,
    )

    @Test
    fun `a new rating is the current one`() {
        assertTrue(rated().isCurrent)
    }

    @Test
    fun `records both the rule and the price it carried`() {
        // The redundancy that keeps an amount explainable after the rule is corrected.
        val row = rated()
        assertEquals(7L, row.pricingRuleId)
        assertEquals(0, row.quantity.multiply(row.unitPrice).compareTo(row.amount))
    }

    @Test
    fun `an in-period charge has matching periods`() {
        val row = rated()
        assertEquals(row.originPeriod, row.billingPeriod)
        assertFalse(row.isLateAdjustment)
    }

    @Test
    fun `a late adjustment keeps the period the usage happened in`() {
        // Without origin_period, reconstructing what August actually consumed would be
        // impossible once the charge moved to September.
        val row = rated(billingPeriod = "2026-09-01", originPeriod = "2026-08-01", isLate = true)

        assertEquals(LocalDate.parse("2026-08-01"), row.originPeriod)
        assertEquals(LocalDate.parse("2026-09-01"), row.billingPeriod)
        assertTrue(row.isLateAdjustment)
    }

    @Test
    fun `superseding records the replacement without altering the amount`() {
        // A correction adds a row and points the old one at it. The original figure
        // stays readable so an auditor sees both what was billed and the correction.
        val row = rated()

        row.supersede(replacementId = 2L, reason = "pricing rule 7 corrected")

        assertFalse(row.isCurrent)
        assertEquals(2L, row.supersededBy)
        assertEquals("pricing rule 7 corrected", row.supersededReason)
        assertEquals("5.0000", row.amount.toPlainString())
    }

    @Test
    fun `a row cannot supersede itself`() {
        // Self-reference would make the row simultaneously current and superseded, and
        // a database CHECK rejects it too.
        assertFailsWith<IllegalArgumentException> { rated(id = 5L).supersede(5L, "nonsense") }
    }
}
