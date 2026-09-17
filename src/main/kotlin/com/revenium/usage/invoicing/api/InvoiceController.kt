package com.revenium.usage.invoicing.api

import com.revenium.usage.invoicing.domain.model.InvoiceSummary
import com.revenium.usage.invoicing.domain.model.UsageSummary
import com.revenium.usage.invoicing.domain.port.`in`.ClosePeriodUseCase
import com.revenium.usage.invoicing.domain.port.`in`.SummariseInvoiceUseCase
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@Schema(description = "Charges for one transaction code within one origin period")
data class SummaryLineResponse(
    val transactionCode: String,
    val transactionCount: Long,
    val totalQuantity: BigDecimal,
    val amount: BigDecimal,
    @field:Schema(description = "The period the usage happened in, which may predate this invoice")
    val originPeriod: String,
    @field:Schema(description = "True when this line is a late arrival from a closed period")
    val isAdjustment: Boolean,
)

@Schema(description = "What a customer owes for a period, and why")
data class InvoiceSummaryResponse(
    val customerId: String,
    val period: String,
    val currency: String,
    val status: String,
    val lines: List<SummaryLineResponse>,
    @field:Schema(description = "Charges for usage that happened in this period")
    val currentPeriodAmount: BigDecimal,
    @field:Schema(description = "Charges carried in from earlier periods that had already closed")
    val adjustmentAmount: BigDecimal,
    @field:Schema(description = "Always exactly currentPeriodAmount + adjustmentAmount")
    val totalAmount: BigDecimal,
    val transactionCount: Long,
)

@Schema(description = "Usage for one transaction code, totalled across every period in the range")
data class UsageLineResponse(
    val transactionCode: String,
    val transactionCount: Long,
    val totalQuantity: BigDecimal,
    val amount: BigDecimal,
)

@Schema(description = "One period covered by a range summary, and whether it was already billed")
data class PeriodStatusResponse(val period: String, val status: String)

@Schema(description = "What a customer used across a span of billing periods")
data class UsageSummaryResponse(
    val customerId: String,
    val from: String,
    val to: String,
    val currency: String,
    @field:Schema(
        description = "Every period in the range with its status. A range may cover both " +
            "closed and open periods, which is why this is a list rather than one status.",
    )
    val periods: List<PeriodStatusResponse>,
    val lines: List<UsageLineResponse>,
    val totalAmount: BigDecimal,
    val transactionCount: Long,
)

@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "Invoices", description = "Invoice summaries and period close")
class InvoiceController(
    private val summarise: SummariseInvoiceUseCase,
    private val closePeriod: ClosePeriodUseCase,
) {

    @GetMapping("/summary")
    @Operation(
        summary = "Summarise a customer's charges for a period",
        description = """
            An open period is aggregated from rated transactions on every request, so the
            figures always reflect the latest rating. A closed period is read back exactly
            as it was frozen.

            `currentPeriodAmount` and `adjustmentAmount` are reported separately so a
            reader can distinguish what was consumed this period from what is merely
            being billed in it. Their sum is always `totalAmount`.
        """,
    )
    fun summary(
        @Parameter(description = "Authoritative tenant identity", required = true)
        @RequestHeader("X-Tenant-Id") tenantHeader: String,
        @RequestParam customerId: String,
        @Parameter(description = "Billing period as YYYY-MM", example = "2026-08")
        @RequestParam period: String,
    ): ResponseEntity<InvoiceSummaryResponse> =
        ResponseEntity.ok(
            summarise.summarise(CustomerId(customerId), BillingPeriod.parse(period)).toResponse()
        )

    @GetMapping("/usage")
    @Operation(
        summary = "Total a customer's usage across a range of periods",
        description = """
            Counts, quantities and amounts grouped by transaction code, plus a total, for
            every period from `from` to `to` inclusive.

            Deliberately not an invoice. A range can cover both closed and open periods,
            so there is no single status to report — `periods` lists each one and whether
            it was already billed. Closed periods contribute exactly the figures they were
            billed at, never a re-aggregation, so this can never disagree with an invoice
            already sent.

            Bounded to 24 months: the range is built one period at a time to preserve that
            guarantee, so its cost is linear in the span.
        """,
    )
    fun usage(
        @Parameter(description = "Authoritative tenant identity", required = true)
        @RequestHeader("X-Tenant-Id") tenantHeader: String,
        @RequestParam customerId: String,
        @Parameter(description = "First period in the range, as YYYY-MM", example = "2026-06")
        @RequestParam from: String,
        @Parameter(description = "Last period, inclusive, as YYYY-MM", example = "2026-08")
        @RequestParam to: String,
    ): ResponseEntity<UsageSummaryResponse> =
        ResponseEntity.ok(
            summarise.summariseRange(
                CustomerId(customerId),
                BillingPeriod.parse(from),
                BillingPeriod.parse(to),
            ).toResponse()
        )

    @PostMapping("/close")
    @Operation(
        summary = "Close a billing period",
        description = """
            Freezes the period's totals into an invoice and its lines. A closed invoice is
            never modified again: usage that arrives afterwards is billed as an adjustment
            in the open period, carrying its original period for traceability.

            Administrative, and deliberately explicit rather than scheduled, so the
            behaviour is demonstrable. Closing an already-closed period is refused.
        """,
    )
    fun close(
        @RequestHeader("X-Tenant-Id") tenantHeader: String,
        @RequestParam customerId: String,
        @RequestParam period: String,
    ): ResponseEntity<InvoiceSummaryResponse> =
        ResponseEntity.ok(
            closePeriod.closePeriod(CustomerId(customerId), BillingPeriod.parse(period)).toResponse()
        )
}

internal fun InvoiceSummary.toResponse() = InvoiceSummaryResponse(
    customerId = customerId.value,
    period = period.toString(),
    currency = currency.currencyCode,
    status = status.name,
    lines = lines.map {
        SummaryLineResponse(
            transactionCode = it.transactionCode.value,
            transactionCount = it.transactionCount,
            totalQuantity = it.totalQuantity.value,
            amount = it.amount.amount,
            originPeriod = it.originPeriod.toString(),
            isAdjustment = it.isAdjustment,
        )
    },
    currentPeriodAmount = currentPeriodAmount.amount,
    adjustmentAmount = adjustmentAmount.amount,
    totalAmount = totalAmount.amount,
    transactionCount = transactionCount,
)

internal fun UsageSummary.toResponse() = UsageSummaryResponse(
    customerId = customerId.value,
    from = from.toString(),
    to = to.toString(),
    currency = currency.currencyCode,
    periods = periods.map { PeriodStatusResponse(it.period.toString(), it.status.name) },
    lines = lines.map {
        UsageLineResponse(
            transactionCode = it.transactionCode.value,
            transactionCount = it.transactionCount,
            totalQuantity = it.totalQuantity.value,
            amount = it.amount.amount,
        )
    },
    totalAmount = totalAmount.amount,
    transactionCount = transactionCount,
)
