package com.revenium.usage.rating.domain.model

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.shared.domain.UnitPrice
import com.revenium.usage.tenancy.TenantId
import java.time.Instant

/**
 * The financial result of rating one event.
 *
 * Records both [pricingRuleId] and [unitPrice]: the rule may be corrected later, and this
 * row must keep explaining its own amount without depending on the pricing table's
 * current state.
 *
 * [originPeriod] is when the usage happened; [billingPeriod] is when it is charged. They
 * differ only for a late arrival.
 */
data class RatedTransaction(
    val tenantId: TenantId,
    val rawEventId: Long,
    val customerId: CustomerId,
    val transactionCode: TransactionCode,
    val pricingRuleId: Long,
    val unitPrice: UnitPrice,
    val quantity: Quantity,
    val amount: Money,
    val occurredAt: Instant,
    val billingPeriod: BillingPeriod,
    val originPeriod: BillingPeriod,
    val isLateAdjustment: Boolean = false,
    val ratedAt: Instant = Instant.now(),
    /** Set when a correction has replaced this row. Corrections append; nothing is updated. */
    val supersededBy: Long? = null,
    val supersededReason: String? = null,
    val id: Long = 0,
) {
    init {
        require(isLateAdjustment == (billingPeriod != originPeriod)) {
            "A late adjustment must be billed in a different period from its origin"
        }
        // Null-check first: `supersededBy != id` already covers null, so the trailing
        // `|| supersededBy == null` it replaced was unreachable and the compiler warned
        // on it. Same behaviour, no dead branch.
        require(supersededBy == null || supersededBy != id) {
            "A rated transaction cannot supersede itself"
        }
    }

    val isCurrent: Boolean get() = supersededBy == null

    /**
     * Returns a copy marked as replaced by [replacementId].
     *
     * The amount is untouched: an auditor must see both what was billed and what it was
     * corrected to.
     */
    fun supersededBy(replacementId: Long, reason: String): RatedTransaction {
        require(replacementId != id) { "A rated transaction cannot supersede itself" }
        return copy(supersededBy = replacementId, supersededReason = reason)
    }
}
