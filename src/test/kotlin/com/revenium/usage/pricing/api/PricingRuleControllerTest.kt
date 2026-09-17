package com.revenium.usage.pricing.api

import com.ninjasquad.springmockk.MockkBean
import com.revenium.usage.pricing.domain.model.PricingRule
import com.revenium.usage.pricing.domain.port.out.PricingRuleLookup
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.shared.domain.UnitPrice
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
import java.util.Currency

@WebMvcTest(PricingRuleController::class)
class PricingRuleControllerTest(@Autowired val mvc: MockMvc) {

    @MockkBean
    private lateinit var pricingRules: PricingRuleLookup

    @BeforeEach
    fun enterTenantScope() {
        // The controller is @RequiresTenant and the filter is not part of this slice,
        // so the scope is established directly.
        TenantContext.set(TenantId("tenant-a"))
    }

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    private fun rule(tenantId: String = "tenant-a", id: Long = 7L) = PricingRule(
        tenantId = TenantId(tenantId),
        transactionCode = TransactionCode("VEHICLE_REGISTRATION"),
        unitPrice = UnitPrice(BigDecimal("2.500000")),
        currency = Currency.getInstance("USD"),
        effectiveFrom = Instant.parse("2026-07-01T00:00:00Z"),
        effectiveTo = null,
        description = "Rate increase from H2 2026",
        id = id,
    )

    @Test
    fun `lists the tenant's rules`() {
        every { pricingRules.findAllFor(TenantId("tenant-a")) } returns listOf(rule())

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
        every { pricingRules.findById(TenantId("tenant-a"), 7L) } returns rule()

        mvc.perform(get("/api/v1/pricing-rules/7").header("X-Tenant-Id", "tenant-a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.unitPrice").value(2.500000))
            .andExpect(jsonPath("$.effectiveFrom").exists())
    }

    @Test
    fun `returns 404 for a rule belonging to another tenant`() {
        // A 404 rather than a leak: the lookup scopes by tenant as well as id, so the
        // response is identical whether the rule is absent or simply not theirs.
        every { pricingRules.findById(TenantId("tenant-a"), 7L) } returns null

        mvc.perform(get("/api/v1/pricing-rules/7").header("X-Tenant-Id", "tenant-a"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `returns 404 for a rule that does not exist`() {
        every { pricingRules.findById(TenantId("tenant-a"), 99L) } returns null

        mvc.perform(get("/api/v1/pricing-rules/99").header("X-Tenant-Id", "tenant-a"))
            .andExpect(status().isNotFound)
    }
}
