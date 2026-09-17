package com.revenium.usage.rating.infrastructure.persistence

import com.revenium.usage.rating.domain.model.RatedTransaction
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

/**
 * A mapper that silently drops a field is the failure mode this guards against: the
 * amount would still be written, just under the wrong period or without its rule, and
 * nothing would say so until an audit.
 */
class RatedTransactionEntityTest {

    private val usd: Currency = Currency.getInstance("USD")

    private fun rated(late: Boolean = false) = RatedTransaction(
        tenantId = TenantId("tenant-a"),
        rawEventId = 11L,
        customerId = CustomerId("customer-42"),
        transactionCode = TransactionCode("VEHICLE_REGISTRATION"),
        pricingRuleId = 7L,
        unitPrice = UnitPrice(BigDecimal("2.500000")),
        quantity = Quantity(BigDecimal("2")),
        amount = Money.of(BigDecimal("5.0000"), usd),
        occurredAt = Instant.parse("2026-08-15T14:22:31Z"),
        billingPeriod = BillingPeriod.parse(if (late) "2026-09" else "2026-08"),
        originPeriod = BillingPeriod.parse("2026-08"),
        isLateAdjustment = late,
        ratedAt = Instant.parse("2026-09-16T12:00:00Z"),
        id = 3L,
    )

    @Test
    fun `a round trip preserves every field`() {
        val original = rated()

        assertEquals(original, RatedTransactionEntity.fromDomain(original).toDomain())
    }

    @Test
    fun `a late adjustment keeps both of its periods`() {
        // The pair that makes an adjustment explainable: charged in September, incurred
        // in August. Collapsing them would produce a total nobody could trace.
        val mapped = RatedTransactionEntity.fromDomain(rated(late = true)).toDomain()

        assertEquals(BillingPeriod.parse("2026-08"), mapped.originPeriod)
        assertEquals(BillingPeriod.parse("2026-09"), mapped.billingPeriod)
    }

    @Test
    fun `the amount survives as an exact decimal with its currency`() {
        // The whole reason money is BigDecimal: a mapper that went via a primitive
        // would round here, silently, once per transaction.
        val mapped = RatedTransactionEntity.fromDomain(rated()).toDomain()

        assertEquals("5.0000", mapped.amount.amount.toPlainString())
        assertEquals(usd, mapped.amount.currency)
        assertEquals("2.500000", mapped.unitPrice.value.toPlainString())
    }

    @Test
    fun `a superseded row carries its replacement and reason`() {
        val superseded = rated().supersededBy(replacementId = 9L, reason = "rule 7 corrected")

        val mapped = RatedTransactionEntity.fromDomain(superseded).toDomain()

        assertEquals(9L, mapped.supersededBy)
        assertEquals("rule 7 corrected", mapped.supersededReason)
        assertEquals("5.0000", mapped.amount.amount.toPlainString())
    }
}
