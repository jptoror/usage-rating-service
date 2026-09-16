package com.revenium.usage.invoicing.api

import com.ninjasquad.springmockk.MockkBean
import com.revenium.usage.invoicing.application.InvoiceService
import com.revenium.usage.invoicing.domain.InvoiceStatus
import com.revenium.usage.invoicing.domain.InvoiceSummary
import com.revenium.usage.invoicing.domain.SummaryLine
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import io.mockk.every
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.util.Currency
import kotlin.test.assertEquals

@WebMvcTest(InvoiceController::class)
class InvoiceControllerTest(@Autowired val mvc: MockMvc) {

    @MockkBean
    private lateinit var invoiceService: InvoiceService

    private val usd: Currency = Currency.getInstance("USD")
    private val september = BillingPeriod.parse("2026-09")
    private val august = BillingPeriod.parse("2026-08")

    private fun summary(status: InvoiceStatus = InvoiceStatus.OPEN) = InvoiceSummary.from(
        customerId = CustomerId("customer-42"),
        period = september,
        currency = usd,
        lines = listOf(
            SummaryLine("VEHICLE_REGISTRATION", 620, BigDecimal("1240"), Money.of(BigDecimal("1240"), usd), september, false),
            SummaryLine("VEHICLE_REGISTRATION", 3, BigDecimal("15"), Money.of(BigDecimal("37.5"), usd), august, true),
        ),
        status = status,
    )

    @Test
    fun `returns the summary with adjustments reported separately`() {
        // The split is the whole point of the late-arrival policy being honest: what was
        // consumed this period versus what is merely charged in it.
        every { invoiceService.summarise(any(), any()) } returns summary()

        mvc.perform(
            get("/api/v1/invoices/summary")
                .header("X-Tenant-Id", "tenant-a")
                .param("customerId", "customer-42")
                .param("period", "2026-09")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.currentPeriodAmount").value(1240.0000))
            .andExpect(jsonPath("$.adjustmentAmount").value(37.5000))
            .andExpect(jsonPath("$.totalAmount").value(1277.5000))
            .andExpect(jsonPath("$.status").value("OPEN"))
    }

    @Test
    fun `an adjustment line keeps the period the usage happened in`() {
        every { invoiceService.summarise(any(), any()) } returns summary()

        mvc.perform(
            get("/api/v1/invoices/summary")
                .header("X-Tenant-Id", "tenant-a")
                .param("customerId", "customer-42")
                .param("period", "2026-09")
        )
            .andExpect(jsonPath("$.lines[1].isAdjustment").value(true))
            .andExpect(jsonPath("$.lines[1].originPeriod").value("2026-08"))
    }

    @Test
    fun `parses the period parameter into a billing period`() {
        val period = slot<BillingPeriod>()
        every { invoiceService.summarise(any(), capture(period)) } returns summary()

        mvc.perform(
            get("/api/v1/invoices/summary")
                .header("X-Tenant-Id", "tenant-a")
                .param("customerId", "customer-42")
                .param("period", "2026-09")
        ).andExpect(status().isOk)

        assertEquals(september, period.captured)
    }

    @Test
    fun `requires a tenant header`() {
        mvc.perform(
            get("/api/v1/invoices/summary")
                .param("customerId", "customer-42")
                .param("period", "2026-09")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `closing returns the frozen summary`() {
        every { invoiceService.closePeriod(any(), any()) } returns summary(InvoiceStatus.CLOSED)

        mvc.perform(
            post("/api/v1/invoices/close")
                .header("X-Tenant-Id", "tenant-a")
                .param("customerId", "customer-42")
                .param("period", "2026-09")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CLOSED"))
            .andExpect(jsonPath("$.totalAmount").value(1277.5000))
    }
}
