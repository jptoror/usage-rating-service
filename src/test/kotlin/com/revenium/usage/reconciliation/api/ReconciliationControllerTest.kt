package com.revenium.usage.reconciliation.api

import com.ninjasquad.springmockk.MockkBean
import com.revenium.usage.reconciliation.application.ReconciliationService
import com.revenium.usage.reconciliation.domain.EventState
import com.revenium.usage.reconciliation.domain.ReconciliationLine
import com.revenium.usage.reconciliation.domain.ReconciliationReport
import com.revenium.usage.reconciliation.domain.StateCount
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import io.mockk.every
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

@WebMvcTest(ReconciliationController::class)
class ReconciliationControllerTest(@Autowired val mvc: MockMvc) {

    @MockkBean
    private lateinit var reconciliationService: ReconciliationService

    private val usd: Currency = Currency.getInstance("USD")
    private val august = BillingPeriod.parse("2026-08")
    private val now = Instant.parse("2026-09-16T12:00:00Z")

    private fun report(received: Long, rated: Long, duplicates: Long = 0, rejected: Long = 0) =
        ReconciliationReport(
            customerId = CustomerId("customer-42"),
            period = august,
            receivedCount = received,
            states = listOf(
                StateCount(EventState.RATED, rated, Money.of(BigDecimal("100"), usd)),
                StateCount(EventState.DUPLICATE, duplicates, null),
                StateCount(EventState.REJECTED, rejected, null),
            ),
            billedAmount = Money.of(BigDecimal("100"), usd),
            generatedAt = now,
        )

    private fun request(path: String) = get(path)
        .header("X-Tenant-Id", "tenant-a")
        .param("customerId", "customer-42")
        .param("period", "2026-08")

    @Test
    fun `reports balanced when the totals reconcile`() {
        every { reconciliationService.report(any(), any()) } returns report(received = 10, rated = 8, duplicates = 1, rejected = 1)

        mvc.perform(request("/api/v1/reconciliation/report"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanced").value(true))
            .andExpect(jsonPath("$.imbalance").doesNotExist())
            .andExpect(jsonPath("$.receivedCount").value(10))
    }

    @Test
    fun `surfaces an imbalance instead of hiding it`() {
        // A report that quietly showed plausible numbers while the arithmetic failed
        // would be worse than no report at all.
        every { reconciliationService.report(any(), any()) } returns report(received = 10, rated = 5)

        mvc.perform(request("/api/v1/reconciliation/report"))
            .andExpect(jsonPath("$.balanced").value(false))
            .andExpect(jsonPath("$.imbalance").exists())
    }

    @Test
    fun `lines carry everything needed to re-derive an amount`() {
        every { reconciliationService.lines(any(), any(), any()) } returns listOf(
            ReconciliationLine(
                eventId = "73d4e120-77d0-4f11-a6d2-f3b43b430d9c",
                rawEventId = 1L,
                transactionCode = "VEHICLE_REGISTRATION",
                occurredAt = now,
                receivedAt = now,
                quantity = BigDecimal("2"),
                unitPrice = BigDecimal("2.500000"),
                amount = Money.of(BigDecimal("5"), usd),
                pricingRuleId = 7L,
                originPeriod = august,
                billingPeriod = august,
                isLateAdjustment = false,
                state = EventState.RATED,
            )
        )

        mvc.perform(request("/api/v1/reconciliation/lines"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].eventId").value("73d4e120-77d0-4f11-a6d2-f3b43b430d9c"))
            .andExpect(jsonPath("$[0].quantity").value(2))
            .andExpect(jsonPath("$[0].unitPrice").value(2.500000))
            .andExpect(jsonPath("$[0].amount").value(5.0000))
            .andExpect(jsonPath("$[0].pricingRuleId").value(7))
    }

    @Test
    fun `requires a tenant header`() {
        mvc.perform(
            get("/api/v1/reconciliation/report")
                .param("customerId", "customer-42")
                .param("period", "2026-08")
        ).andExpect(status().isBadRequest)
    }
}
