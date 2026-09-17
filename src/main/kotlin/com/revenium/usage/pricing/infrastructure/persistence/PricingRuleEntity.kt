package com.revenium.usage.pricing.infrastructure.persistence

import com.revenium.usage.pricing.domain.model.PricingRule
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.shared.domain.UnitPrice
import com.revenium.usage.tenancy.TenantId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * Persistence mapping for a pricing rule.
 *
 * Mirrors the table, not the domain: primitives and nullable columns, no invariants. The
 * rules live in [PricingRule], which this converts to and from.
 */
@Entity
@Table(name = "pricing_rule")
class PricingRuleEntity(

    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,

    @Column(name = "transaction_code", nullable = false, updatable = false)
    val transactionCode: String,

    @Column(name = "unit_price", nullable = false, updatable = false, precision = 19, scale = 6)
    val unitPrice: BigDecimal,

    // columnDefinition, not just length: the column is CHAR(3) (bpchar) and
    // Hibernate would otherwise infer varchar, which ddl-auto=validate rejects.
    @Column(name = "currency", nullable = false, updatable = false, columnDefinition = "bpchar(3)")
    val currency: String,

    @Column(name = "effective_from", nullable = false, updatable = false)
    val effectiveFrom: Instant,

    /** `null` means the rule has no end date. */
    @Column(name = "effective_to", updatable = false)
    val effectiveTo: Instant? = null,

    @Column(name = "description", updatable = false)
    val description: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
) {

    fun toDomain() = PricingRule(
        tenantId = TenantId(tenantId),
        transactionCode = TransactionCode(transactionCode),
        unitPrice = UnitPrice(unitPrice),
        currency = Currency.getInstance(currency),
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
        description = description,
        createdAt = createdAt,
        id = id,
    )

    companion object {
        fun fromDomain(rule: PricingRule) = PricingRuleEntity(
            tenantId = rule.tenantId.value,
            transactionCode = rule.transactionCode.value,
            unitPrice = rule.unitPrice.value,
            currency = rule.currency.currencyCode,
            effectiveFrom = rule.effectiveFrom,
            effectiveTo = rule.effectiveTo,
            description = rule.description,
            createdAt = rule.createdAt,
            id = rule.id,
        )
    }
}
