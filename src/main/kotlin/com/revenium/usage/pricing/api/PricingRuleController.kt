package com.revenium.usage.pricing.api

import com.revenium.usage.pricing.infrastructure.PricingRuleJpaRepository
import com.revenium.usage.tenancy.RequiresTenant
import com.revenium.usage.tenancy.TenantContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@Schema(description = "A per-unit price valid over [effectiveFrom, effectiveTo)")
data class PricingRuleResponse(
    val id: Long,
    val transactionCode: String,
    val unitPrice: BigDecimal,
    val currency: String,
    @field:Schema(description = "Inclusive")
    val effectiveFrom: Instant,
    @field:Schema(description = "Exclusive; null means in effect indefinitely")
    val effectiveTo: Instant?,
    val description: String?,
)

/**
 * Read-only access to pricing rules.
 *
 * The last step of tracing an amount: a reconciliation line names the rule that priced
 * it, and this returns that rule so the figure can be verified independently.
 *
 * Deliberately read-only. Price changes are an administrative operation with real
 * financial consequences, and exposing them over the same API as usage ingestion is out
 * of scope for this exercise -- stated in the README rather than half-built.
 */
@RestController
@RequestMapping("/api/v1/pricing-rules")
@Tag(name = "Pricing rules", description = "Effective-dated prices (read-only)")
@RequiresTenant
class PricingRuleController(private val repository: PricingRuleJpaRepository) {

    @GetMapping
    @Operation(summary = "List the tenant's pricing rules")
    @Transactional(readOnly = true)
    fun list(
        @RequestHeader("X-Tenant-Id") tenantHeader: String,
    ): ResponseEntity<List<PricingRuleResponse>> {
        val tenant = TenantContext.current()
        return ResponseEntity.ok(
            repository.findByTenantIdOrderByTransactionCodeAscEffectiveFromAsc(tenant.value)
                .map { it.toResponse() }
        )
    }

    @GetMapping("/{id}")
    @Operation(
        summary = "Fetch one pricing rule",
        description = "Verifies a rated amount: quantity x this rule's unitPrice is the charge.",
    )
    @Transactional(readOnly = true)
    fun byId(
        @RequestHeader("X-Tenant-Id") tenantHeader: String,
        @PathVariable id: Long,
    ): ResponseEntity<PricingRuleResponse> {
        val tenant = TenantContext.current()
        // Row-level security already restricts this, but the tenant is checked
        // explicitly too: a 404 rather than a leak, even if a policy were ever dropped.
        val rule = repository.findById(id).orElse(null)
            ?.takeIf { it.tenantId == tenant.value }
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(rule.toResponse())
    }
}

private fun com.revenium.usage.pricing.domain.PricingRule.toResponse() = PricingRuleResponse(
    id = id,
    transactionCode = transactionCode,
    unitPrice = unitPrice,
    currency = currency,
    effectiveFrom = effectiveFrom,
    effectiveTo = effectiveTo,
    description = description,
)
