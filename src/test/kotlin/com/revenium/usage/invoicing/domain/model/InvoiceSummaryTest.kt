package com.revenium.usage.invoicing.domain.model

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvoiceSummaryTest {

    private val usd: Currency = Currency.getInstance("USD")
    private val customer = CustomerId("customer-42")
    private val september = BillingPeriod.parse("2026-09")
    private val august = BillingPeriod.parse("2026-08")

    private fun line(
        code: String = "VEHICLE_REGISTRATION",
        amount: String = "10.0000",
        count: Long = 1,
        quantity: String = "1",
        originPeriod: BillingPeriod = september,
        isAdjustment: Boolean = false,
    ) = SummaryLine(
        transactionCode = TransactionCode(code),
        transactionCount = count,
        totalQuantity = Quantity(BigDecimal(quantity)),
        amount = Money.of(BigDecimal(amount), usd),
        originPeriod = originPeriod,
        isAdjustment = isAdjustment,
    )

    private fun summaryOf(vararg lines: SummaryLine) =
        InvoiceSummary.from(customer, september, usd, lines.toList(), InvoiceStatus.OPEN)

    @Test
    fun `the total is exactly the sum of the line amounts`() {
        // The property a reviewer checks first: the total must be re-derivable by adding
        // up the lines, with no rounding difference anywhere.
        val summary = summaryOf(
            line(amount = "0.3333"),
            line(code = "TITLE_TRANSFER", amount = "0.3333"),
            line(code = "RECORD_LOOKUP", amount = "0.3333"),
        )

        assertEquals("0.9999", summary.totalAmount.amount.toPlainString())
        assertEquals(
            summary.totalAmount.amount,
            summary.lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.amount.amount) },
        )
    }

    @Test
    fun `separates this period's usage from late adjustments`() {
        // The late-arrival policy trades "the period total equals the period's
        // consumption" for "a closed invoice never changes". Reporting both figures is
        // what keeps that trade visible rather than hidden.
        val summary = summaryOf(
            line(amount = "1240.0000", count = 620),
            line(amount = "37.5000", count = 3, originPeriod = august, isAdjustment = true),
        )

        assertEquals("1240.0000", summary.currentPeriodAmount.amount.toPlainString())
        assertEquals("37.5000", summary.adjustmentAmount.amount.toPlainString())
        assertEquals("1277.5000", summary.totalAmount.amount.toPlainString())
    }

    @Test
    fun `an adjustment keeps the period the usage actually happened in`() {
        val summary = summaryOf(line(originPeriod = august, isAdjustment = true))

        // Without this, reconstructing August's real consumption would be impossible.
        assertEquals(august, summary.lines.single().originPeriod)
        assertEquals(september, summary.period)
    }

    @Test
    fun `counts every transaction across all lines`() {
        val summary = summaryOf(
            line(count = 620),
            line(code = "TITLE_TRANSFER", count = 12),
            line(count = 3, originPeriod = august, isAdjustment = true),
        )

        assertEquals(635, summary.transactionCount)
    }

    @Test
    fun `orders current usage before adjustments, oldest adjustment first`() {
        // Deterministic ordering: two runs of the same query must read alike, or a
        // reviewer comparing them would see differences that are not real.
        val july = BillingPeriod.parse("2026-07")
        val summary = summaryOf(
            line(code = "B", originPeriod = august, isAdjustment = true),
            line(code = "A"),
            line(code = "C", originPeriod = july, isAdjustment = true),
        )

        assertEquals(listOf("A", "C", "B"), summary.lines.map { it.transactionCode.value })
    }

    @Test
    fun `an empty period totals zero rather than failing`() {
        // A customer with no usage still gets a summary, so reconciliation can account
        // for the period instead of finding a gap.
        val summary = summaryOf()

        assertTrue(summary.totalAmount.isZero())
        assertTrue(summary.currentPeriodAmount.isZero())
        assertTrue(summary.adjustmentAmount.isZero())
        assertEquals(0, summary.transactionCount)
    }

    @Test
    fun `a period of only adjustments reports zero current usage`() {
        val summary = summaryOf(line(amount = "50.0000", originPeriod = august, isAdjustment = true))

        assertTrue(summary.currentPeriodAmount.isZero())
        assertEquals("50.0000", summary.totalAmount.amount.toPlainString())
    }
}
