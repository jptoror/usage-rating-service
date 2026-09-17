package com.revenium.usage.rating.domain.model

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.shared.domain.UnitPrice
import com.revenium.usage.tenancy.TenantId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RatedTransactionTest {

    private val now = Instant.parse("2026-09-16T12:00:00Z")
    private val usd: Currency = Currency.getInstance("USD")

    private fun rated(
        id: Long = 1L,
        billingPeriod: String = "2026-08",
        originPeriod: String = "2026-08",
        isLate: Boolean = false,
    ) = RatedTransaction(
        tenantId = TenantId("tenant-a"),
        rawEventId = 1L,
        customerId = CustomerId("customer-42"),
        transactionCode = TransactionCode("VEHICLE_REGISTRATION"),
        pricingRuleId = 7L,
        unitPrice = UnitPrice(BigDecimal("2.500000")),
        quantity = Quantity(BigDecimal("2")),
        amount = Money.of(BigDecimal("5.0000"), usd),
        occurredAt = now,
        billingPeriod = BillingPeriod.parse(billingPeriod),
        originPeriod = BillingPeriod.parse(originPeriod),
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
        assertEquals(
            0,
            row.quantity.value.multiply(row.unitPrice.value).compareTo(row.amount.amount),
        )
    }

    @Test
    fun `an in-period charge has matching periods`() {
        val row = rated()
        assertEquals(row.originPeriod, row.billingPeriod)
        assertFalse(row.isLateAdjustment)
    }

    @Test
    fun `a late adjustment keeps the period the usage happened in`() {
        // Without the origin period, reconstructing what August actually consumed would
        // be impossible once the charge moved to September.
        val row = rated(billingPeriod = "2026-09", originPeriod = "2026-08", isLate = true)

        assertEquals(BillingPeriod.parse("2026-08"), row.originPeriod)
        assertEquals(BillingPeriod.parse("2026-09"), row.billingPeriod)
        assertTrue(row.isLateAdjustment)
    }

    @Test
    fun `superseding records the replacement without altering the amount`() {
        // A correction adds a row and points the old one at it. The original figure
        // stays readable so an auditor sees both what was billed and the correction.
        val superseded = rated().supersededBy(replacementId = 2L, reason = "pricing rule 7 corrected")

        assertFalse(superseded.isCurrent)
        assertEquals(2L, superseded.supersededBy)
        assertEquals("pricing rule 7 corrected", superseded.supersededReason)
        assertEquals("5.0000", superseded.amount.amount.toPlainString())
    }

    @Test
    fun `a row cannot supersede itself`() {
        // Self-reference would make the row simultaneously current and superseded, and
        // a database CHECK rejects it too.
        assertFailsWith<IllegalArgumentException> { rated(id = 5L).supersededBy(5L, "nonsense") }
    }

    @Test
    fun `a self-superseding row is rejected at construction, not only via supersededBy`() {
        // The construction path is the one that matters here: it is how toDomain()
        // rebuilds a row read back from the database, where supersededBy is already set.
        // Guarding only inside supersededBy() would let a corrupt row load cleanly.
        assertFailsWith<IllegalArgumentException> {
            rated(id = 5L).copy(supersededBy = 5L, supersededReason = "nonsense")
        }
    }

    @Test
    fun `a row superseded by another row is valid`() {
        val superseded = rated(id = 5L).copy(supersededBy = 6L, supersededReason = "corrected")

        assertFalse(superseded.isCurrent)
        assertEquals(6L, superseded.supersededBy)
    }
}
