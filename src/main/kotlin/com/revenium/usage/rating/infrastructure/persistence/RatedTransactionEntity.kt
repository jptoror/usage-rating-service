package com.revenium.usage.rating.infrastructure.persistence

import com.revenium.usage.rating.domain.model.RatedTransaction
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
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
import java.time.LocalDate
import java.util.Currency

/**
 * Persistence mapping for a rated transaction.
 *
 * Mirrors the table, not the domain: primitives and nullable columns, no invariants. The
 * rules live in [RatedTransaction], which this converts to and from.
 */
@Entity
@Table(name = "rated_transaction")
class RatedTransactionEntity(

    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,

    @Column(name = "raw_event_id", nullable = false, updatable = false)
    val rawEventId: Long,

    @Column(name = "customer_id", nullable = false, updatable = false)
    val customerId: String,

    @Column(name = "transaction_code", nullable = false, updatable = false)
    val transactionCode: String,

    @Column(name = "pricing_rule_id", nullable = false, updatable = false)
    val pricingRuleId: Long,

    @Column(name = "unit_price", nullable = false, updatable = false, precision = 19, scale = 6)
    val unitPrice: BigDecimal,

    @Column(name = "quantity", nullable = false, updatable = false, precision = 19, scale = 6)
    val quantity: BigDecimal,

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    val amount: BigDecimal,

    // columnDefinition, not length: the column is CHAR(3) (bpchar), and Hibernate would
    // otherwise infer varchar, which ddl-auto=validate rejects.
    @Column(name = "currency", nullable = false, updatable = false, columnDefinition = "bpchar(3)")
    val currency: String,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant,

    @Column(name = "billing_period", nullable = false, updatable = false)
    val billingPeriod: LocalDate,

    @Column(name = "origin_period", nullable = false, updatable = false)
    val originPeriod: LocalDate,

    @Column(name = "is_late_adjustment", nullable = false, updatable = false)
    val isLateAdjustment: Boolean = false,

    @Column(name = "rated_at", nullable = false, updatable = false)
    val ratedAt: Instant = Instant.now(),

    @Column(name = "superseded_by")
    var supersededBy: Long? = null,

    @Column(name = "superseded_reason")
    var supersededReason: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
) {

    fun toDomain(): RatedTransaction {
        val currencyInstance = Currency.getInstance(currency)
        return RatedTransaction(
            tenantId = TenantId(tenantId),
            rawEventId = rawEventId,
            customerId = CustomerId(customerId),
            transactionCode = TransactionCode(transactionCode),
            pricingRuleId = pricingRuleId,
            unitPrice = UnitPrice(unitPrice),
            quantity = Quantity(quantity),
            amount = Money(amount, currencyInstance),
            occurredAt = occurredAt,
            billingPeriod = BillingPeriod.of(billingPeriod),
            originPeriod = BillingPeriod.of(originPeriod),
            isLateAdjustment = isLateAdjustment,
            ratedAt = ratedAt,
            supersededBy = supersededBy,
            supersededReason = supersededReason,
            id = id,
        )
    }

    companion object {
        fun fromDomain(rated: RatedTransaction) = RatedTransactionEntity(
            tenantId = rated.tenantId.value,
            rawEventId = rated.rawEventId,
            customerId = rated.customerId.value,
            transactionCode = rated.transactionCode.value,
            pricingRuleId = rated.pricingRuleId,
            unitPrice = rated.unitPrice.value,
            quantity = rated.quantity.value,
            amount = rated.amount.amount,
            currency = rated.amount.currency.currencyCode,
            occurredAt = rated.occurredAt,
            billingPeriod = rated.billingPeriod.startDate,
            originPeriod = rated.originPeriod.startDate,
            isLateAdjustment = rated.isLateAdjustment,
            ratedAt = rated.ratedAt,
            supersededBy = rated.supersededBy,
            supersededReason = rated.supersededReason,
            id = rated.id,
        )
    }
}
