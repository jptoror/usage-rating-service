package com.revenium.usage.invoicing.application

import com.revenium.usage.invoicing.domain.Invoice
import com.revenium.usage.invoicing.domain.InvoiceLine
import com.revenium.usage.invoicing.domain.InvoiceStatus
import com.revenium.usage.invoicing.infrastructure.InvoiceJpaRepository
import com.revenium.usage.invoicing.infrastructure.InvoiceLineJpaRepository
import com.revenium.usage.rating.domain.RatedTransaction
import com.revenium.usage.rating.infrastructure.RatedTransactionRepository
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.MissingTenantException
import com.revenium.usage.tenancy.TenantContext
import com.revenium.usage.tenancy.TenantId
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Currency
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InvoiceServiceTest {

    private val now = Instant.parse("2026-09-16T12:00:00Z")
    private val usd: Currency = Currency.getInstance("USD")
    private val tenant = TenantId("tenant-a")
    private val customer = CustomerId("customer-42")
    private val september = BillingPeriod.parse("2026-09")
    private val august = BillingPeriod.parse("2026-08")

    private val ratedTransactions = mockk<RatedTransactionRepository>()
    private val invoices = mockk<InvoiceJpaRepository> {
        every { save(any()) } answers { firstArg() }
    }
    private val invoiceLines = mockk<InvoiceLineJpaRepository> {
        every { saveAll(any<List<InvoiceLine>>()) } answers { firstArg() }
    }

    private val service = InvoiceService(
        ratedTransactions = ratedTransactions,
        invoices = invoices,
        invoiceLines = invoiceLines,
        defaultCurrency = usd,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    private fun rated(
        code: String = "VEHICLE_REGISTRATION",
        amount: String = "5.0000",
        quantity: String = "2",
        originPeriod: BillingPeriod = september,
        isLate: Boolean = false,
    ) = RatedTransaction(
        tenantId = tenant.value,
        rawEventId = 1L,
        customerId = customer.value,
        transactionCode = code,
        pricingRuleId = 1L,
        unitPrice = BigDecimal("2.500000"),
        quantity = BigDecimal(quantity),
        amount = BigDecimal(amount),
        currency = "USD",
        occurredAt = now,
        billingPeriod = september.startDate,
        originPeriod = originPeriod.startDate,
        isLateAdjustment = isLate,
    )

    private fun noInvoiceYet() =
        every { invoices.findByTenantIdAndCustomerIdAndPeriodStart(any(), any(), any()) } returns null

    private fun rating(vararg rows: RatedTransaction) =
        every { ratedTransactions.findCurrentForBillingPeriod(any(), any(), any()) } returns rows.toList()

    // --- summarising an open period ----------------------------------------

    @Test
    fun `aggregates rated transactions by code`() {
        noInvoiceYet()
        rating(rated(), rated(), rated(code = "TITLE_TRANSFER", amount = "7.2500", quantity = "1"))

        val summary = TenantContext.runAs(tenant) { service.summarise(customer, september) }

        assertEquals(2, summary.lines.size)
        assertEquals("17.2500", summary.totalAmount.amount.toPlainString())
        assertEquals(3, summary.transactionCount)
    }

    @Test
    fun `sums already-rounded amounts rather than recalculating`() {
        // Recalculating from quantity x price would round the aggregate differently and
        // produce a total that no longer matches its own lines.
        noInvoiceYet()
        rating(
            rated(code = "RECORD_LOOKUP", amount = "0.0233", quantity = "7"),
            rated(code = "RECORD_LOOKUP", amount = "0.0233", quantity = "7"),
            rated(code = "RECORD_LOOKUP", amount = "0.0233", quantity = "7"),
        )

        val summary = TenantContext.runAs(tenant) { service.summarise(customer, september) }

        assertEquals("0.0699", summary.totalAmount.amount.toPlainString())
    }

    @Test
    fun `separates adjustments from this period's usage`() {
        noInvoiceYet()
        rating(
            rated(amount = "100.0000"),
            rated(amount = "25.0000", originPeriod = august, isLate = true),
        )

        val summary = TenantContext.runAs(tenant) { service.summarise(customer, september) }

        assertEquals("100.0000", summary.currentPeriodAmount.amount.toPlainString())
        assertEquals("25.0000", summary.adjustmentAmount.amount.toPlainString())
        assertEquals("125.0000", summary.totalAmount.amount.toPlainString())
    }

    @Test
    fun `an empty period totals zero in the default currency`() {
        // No transactions means no currency to infer, and the period must still be
        // reportable rather than failing.
        noInvoiceYet()
        rating()

        val summary = TenantContext.runAs(tenant) { service.summarise(customer, september) }

        assertTrue(summary.totalAmount.isZero())
        assertEquals(usd, summary.currency)
    }

    @Test
    fun `takes the currency from the transactions themselves`() {
        noInvoiceYet()
        val eurRow = RatedTransaction(
            tenantId = tenant.value, rawEventId = 1L, customerId = customer.value,
            transactionCode = "CODE", pricingRuleId = 1L,
            unitPrice = BigDecimal("3.750000"), quantity = BigDecimal("2"),
            amount = BigDecimal("7.5000"), currency = "EUR", occurredAt = now,
            billingPeriod = september.startDate, originPeriod = september.startDate,
        )
        rating(eurRow)

        val summary = TenantContext.runAs(tenant) { service.summarise(customer, september) }

        assertEquals("EUR", summary.currency.currencyCode)
    }

    // --- summarising a closed period ---------------------------------------

    @Test
    fun `reads a closed invoice back rather than re-aggregating it`() {
        // Re-aggregating could report a different figure than the one already billed,
        // which is precisely what closing exists to prevent.
        val closed = Invoice(
            tenantId = tenant.value, customerId = customer.value,
            periodStart = september.startDate, periodEnd = september.endDate,
            status = InvoiceStatus.CLOSED, currency = "USD",
            currentPeriodAmount = BigDecimal("100.0000"),
            adjustmentAmount = BigDecimal("25.0000"),
            totalAmount = BigDecimal("125.0000"),
            transactionCount = 7, closedAt = now, id = 1L,
        )
        every { invoices.findByTenantIdAndCustomerIdAndPeriodStart(any(), any(), any()) } returns closed
        every { invoiceLines.findByInvoiceIdOrderByOriginPeriodAscTransactionCodeAsc(1L) } returns listOf(
            InvoiceLine(
                tenantId = tenant.value, invoiceId = 1L, transactionCode = "VEHICLE_REGISTRATION",
                transactionCount = 7, totalQuantity = BigDecimal("14"),
                amount = BigDecimal("125.0000"), originPeriod = september.startDate,
            )
        )

        val summary = TenantContext.runAs(tenant) { service.summarise(customer, september) }

        assertEquals(InvoiceStatus.CLOSED, summary.status)
        assertEquals("125.0000", summary.totalAmount.amount.toPlainString())
        assertEquals(7, summary.transactionCount)
    }

    // --- closing -----------------------------------------------------------

    @Test
    fun `closing freezes the aggregate onto the invoice`() {
        noInvoiceYet()
        rating(rated(amount = "100.0000"), rated(amount = "25.0000", originPeriod = august, isLate = true))

        val persisted = slot<Invoice>()
        every { invoices.save(capture(persisted)) } answers { firstArg() }

        val summary = TenantContext.runAs(tenant) { service.closePeriod(customer, september) }

        assertEquals(InvoiceStatus.CLOSED, summary.status)
        assertEquals(InvoiceStatus.CLOSED, persisted.captured.status)
        assertEquals("125.0000", persisted.captured.totalAmount.toPlainString())
        assertEquals(now, persisted.captured.closedAt)
    }

    @Test
    fun `closing writes one line per code and origin period`() {
        // This period's consumption and an adjustment from August are separate lines:
        // merging them would give a correct total nobody could explain.
        noInvoiceYet()
        rating(rated(amount = "100.0000"), rated(amount = "25.0000", originPeriod = august, isLate = true))

        val lines = slot<List<InvoiceLine>>()
        every { invoiceLines.saveAll(capture(lines)) } answers { firstArg() }

        TenantContext.runAs(tenant) { service.closePeriod(customer, september) }

        assertEquals(2, lines.captured.size)
        assertNotNull(lines.captured.firstOrNull { it.isAdjustment })
        assertEquals(august.startDate, lines.captured.first { it.isAdjustment }.originPeriod)
    }

    @Test
    fun `refuses to close an already closed period`() {
        every { invoices.findByTenantIdAndCustomerIdAndPeriodStart(any(), any(), any()) } returns
            Invoice(
                tenantId = tenant.value, customerId = customer.value,
                periodStart = september.startDate, periodEnd = september.endDate,
                status = InvoiceStatus.CLOSED, currency = "USD", closedAt = now, id = 1L,
            )

        assertFailsWith<IllegalStateException> {
            TenantContext.runAs(tenant) { service.closePeriod(customer, september) }
        }
    }

    @Test
    fun `closes an empty period at zero`() {
        noInvoiceYet()
        rating()

        val summary = TenantContext.runAs(tenant) { service.closePeriod(customer, september) }

        assertTrue(summary.totalAmount.isZero())
        assertEquals(InvoiceStatus.CLOSED, summary.status)
    }

    // --- tenancy -----------------------------------------------------------

    @Test
    fun `refuses to summarise with no tenant in scope`() {
        assertFailsWith<MissingTenantException> { service.summarise(customer, september) }
    }

    @Test
    fun `scopes the query to the tenant in scope`() {
        noInvoiceYet()
        val queriedTenant = slot<String>()
        every {
            ratedTransactions.findCurrentForBillingPeriod(capture(queriedTenant), any(), any())
        } returns emptyList()

        TenantContext.runAs(TenantId("tenant-b")) { service.summarise(customer, september) }

        assertEquals("tenant-b", queriedTenant.captured)
    }
}
