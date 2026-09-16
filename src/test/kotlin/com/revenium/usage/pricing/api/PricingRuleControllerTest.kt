package com.revenium.usage.pricing.api

import com.ninjasquad.springmockk.MockkBean
import com.revenium.usage.pricing.domain.PricingRule
import com.revenium.usage.pricing.infrastructure.PricingRuleJpaRepository
import com.revenium.usage.tenancy.TenantContext
import com.revenium.usage.tenancy.TenantId
import io.mockk.every
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

@WebMvcTest(PricingRuleController::class)
class PricingRuleControllerTest(@Autowired val mvc: MockMvc) {

    @MockkBean
    private lateinit var repository: PricingRuleJpaRepository

    @BeforeEach
    fun enterTenantScope() {
        // The controller is @RequiresTenant and the filter is not part of this slice,
        // so the scope is established directly.
        TenantContext.set(TenantId("tenant-a"))
    }

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    private fun rule(tenantId: String = "tenant-a", id: Long = 7L) = PricingRule(
        tenantId = tenantId,
        transactionCode = "VEHICLE_REGISTRATION",
        unitPrice = BigDecimal("2.500000"),
        currency = "USD",
        effectiveFrom = Instant.parse("2026-07-01T00:00:00Z"),
        effectiveTo = null,
        description = "Rate increase from H2 2026",
        id = id,
    )

    @Test
    fun `lists the tenant's rules`() {
        every { repository.findByTenantIdOrderByTransactionCodeAscEffectiveFromAsc("tenant-a") } returns listOf(rule())

        mvc.perform(get("/api/v1/pricing-rules").header("X-Tenant-Id", "tenant-a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(7))
            .andExpect(jsonPath("$[0].unitPrice").value(2.500000))
            .andExpect(jsonPath("$[0].effectiveTo").doesNotExist())
    }

    @Test
    fun `fetches one rule so an amount can be verified independently`() {
        // The last step of tracing a charge: quantity x this unitPrice must equal the
        // amount on the reconciliation line.
        every { repository.findById(7L) } returns Optional.of(rule())

        mvc.perform(get("/api/v1/pricing-rules/7").header("X-Tenant-Id", "tenant-a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.unitPrice").value(2.500000))
            .andExpect(jsonPath("$.effectiveFrom").exists())
    }

    @Test
    fun `returns 404 for a rule belonging to another tenant`() {
        // A 404 rather than a leak: the tenant is checked here as well as by RLS, so
        // the response is identical whether the rule is absent or simply not theirs.
        every { repository.findById(7L) } returns Optional.of(rule(tenantId = "tenant-b"))

        mvc.perform(get("/api/v1/pricing-rules/7").header("X-Tenant-Id", "tenant-a"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `returns 404 for a rule that does not exist`() {
        every { repository.findById(99L) } returns Optional.empty()

        mvc.perform(get("/api/v1/pricing-rules/99").header("X-Tenant-Id", "tenant-a"))
            .andExpect(status().isNotFound)
    }
}
