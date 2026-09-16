package com.revenium.usage.pricing.infrastructure

import com.revenium.usage.pricing.domain.PricingRule
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PricingRuleLookupAdapterTest {

    private val repository = mockk<PricingRuleJpaRepository>()
    private val lookup = JpaPricingRuleLookup(repository)

    private val occurredAt = Instant.parse("2026-08-15T14:22:31Z")

    @Test
    fun `unwraps typed values for the repository`() {
        // The adapter's whole job: translate domain types to primitives and nothing
        // else. Logic here would be logic the rating unit tests cannot see.
        val rule = PricingRule(
            tenantId = "tenant-a",
            transactionCode = "CODE",
            unitPrice = BigDecimal("2.000000"),
            currency = "USD",
            effectiveFrom = occurredAt,
            id = 5L,
        )
        every { repository.findApplicable("tenant-a", "CODE", occurredAt) } returns rule

        val found = lookup.findApplicable(TenantId("tenant-a"), TransactionCode("CODE"), occurredAt)

        assertEquals(5L, found?.id)
        verify { repository.findApplicable("tenant-a", "CODE", occurredAt) }
    }

    @Test
    fun `returns null when no rule covers the instant`() {
        // Not an exception: a missing rule is an UNRATED event that waits, not a failure.
        every { repository.findApplicable(any(), any(), any()) } returns null

        assertNull(lookup.findApplicable(TenantId("tenant-a"), TransactionCode("CODE"), occurredAt))
    }
}
