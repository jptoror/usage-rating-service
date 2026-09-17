package com.revenium.usage.reconciliation.api

import com.revenium.usage.reconciliation.domain.port.`in`.ReconcileUseCase
import com.revenium.usage.reconciliation.domain.ReconciliationLine
import com.revenium.usage.reconciliation.domain.ReconciliationReport
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@Schema(description = "How many events reached one processing state")
data class StateCountResponse(
    val state: String,
    val count: Long,
    val amount: BigDecimal?,
)

@Schema(description = "Whether what was received accounts for what was billed")
data class ReconciliationReportResponse(
    val customerId: String,
    val period: String,
    @field:Schema(description = "Every delivery that arrived, including rejections and duplicates")
    val receivedCount: Long,
    val states: List<StateCountResponse>,
    val billedAmount: BigDecimal,
    @field:Schema(
        description = """
            True when received = accepted + duplicates + rejected, and
            accepted = rated + invoiced + unrated + failed + quarantined.
            False means a defect, not a rounding difference.
        """,
    )
    val balanced: Boolean,
    @field:Schema(description = "Both sides of the equation that failed, when balanced is false")
    val imbalance: String?,
    val generatedAt: Instant,
)

@Schema(description = "One rated transaction, traced to its event and pricing rule")
data class ReconciliationLineResponse(
    val eventId: String,
    val transactionCode: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    @field:Schema(description = "Always quantity x unitPrice, rounded HALF_UP once at scale 4")
    val amount: BigDecimal,
    @field:Schema(description = "The rule that supplied the price; fetch it to verify the amount")
    val pricingRuleId: Long,
    val originPeriod: String,
    val billingPeriod: String,
    val isLateAdjustment: Boolean,
    val state: String,
)

@RestController
@RequestMapping("/api/v1/reconciliation")
@Tag(name = "Reconciliation", description = "Evidence linking received events to billed amounts")
class ReconciliationController(private val reconciliationService: ReconcileUseCase) {

    @GetMapping("/report")
    @Operation(
        summary = "Reconcile what was received against what was billed",
        description = """
            Counts every received event by the state it reached, and reports whether the
            totals balance:

                received = accepted + duplicates + rejected
                accepted = rated + invoiced + unrated + failed + quarantined

            `balanced: false` indicates a defect in the service, not an accounting
            subtlety, and `imbalance` names the equation that failed.
        """,
    )
    fun report(
        @Parameter(description = "Authoritative tenant identity", required = true)
        @RequestHeader("X-Tenant-Id") tenantHeader: String,
        @RequestParam customerId: String,
        @Parameter(description = "Billing period as YYYY-MM", example = "2026-08")
        @RequestParam period: String,
    ): ResponseEntity<ReconciliationReportResponse> =
        ResponseEntity.ok(
            reconciliationService.report(CustomerId(customerId), BillingPeriod.parse(period)).toResponse()
        )

    @GetMapping("/lines")
    @Operation(
        summary = "List the transactions behind a period's total",
        description = """
            The second step of tracing a figure: a summary line says a code is worth X
            across N transactions; this returns those N rows with the quantity, unit
            price and pricing rule that produced X.

            Every amount is re-derivable by hand from the fields returned here.
        """,
    )
    fun lines(
        @RequestHeader("X-Tenant-Id") tenantHeader: String,
        @RequestParam customerId: String,
        @RequestParam period: String,
        @Parameter(description = "Optional: restrict to one transaction code")
        @RequestParam(required = false) transactionCode: String?,
    ): ResponseEntity<List<ReconciliationLineResponse>> =
        ResponseEntity.ok(
            reconciliationService
                .lines(CustomerId(customerId), BillingPeriod.parse(period), transactionCode)
                .map { it.toResponse() }
        )
}

internal fun ReconciliationReport.toResponse() = ReconciliationReportResponse(
    customerId = customerId.value,
    period = period.toString(),
    receivedCount = receivedCount,
    states = states.map { StateCountResponse(it.state.name, it.count, it.amount?.amount) },
    billedAmount = billedAmount.amount,
    balanced = isBalanced,
    imbalance = imbalanceDescription(),
    generatedAt = generatedAt,
)

internal fun ReconciliationLine.toResponse() = ReconciliationLineResponse(
    eventId = eventId,
    transactionCode = transactionCode,
    occurredAt = occurredAt,
    receivedAt = receivedAt,
    quantity = quantity,
    unitPrice = unitPrice,
    amount = amount.amount,
    pricingRuleId = pricingRuleId,
    originPeriod = originPeriod.toString(),
    billingPeriod = billingPeriod.toString(),
    isLateAdjustment = isLateAdjustment,
    state = state.name,
)
