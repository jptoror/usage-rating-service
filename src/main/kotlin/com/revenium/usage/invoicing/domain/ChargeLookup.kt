package com.revenium.usage.invoicing.domain

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.TenantId
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One charge, as invoicing needs to read it.
 *
 * A projection, not the rating module's entity. Invoicing groups and sums these; it has
 * no business seeing the correction chain or the rating timestamps.
 */
data class Charge(
    val transactionCode: String,
    val quantity: BigDecimal,
    /** Already rounded when the transaction was rated. Summed here, never recalculated. */
    val amount: BigDecimal,
    val currency: String,
    val originPeriod: LocalDate,
    val isLateAdjustment: Boolean,
)

/**
 * Reads the charges that belong in an invoice.
 *
 * A port owned by invoicing -- the module that needs the capability -- and implemented
 * by rating. Invoicing previously injected rating's `JpaRepository` directly, which both
 * violated the dependency rule and handed invoicing `save`, `delete` and `deleteAll` on
 * the financial ledger it is only supposed to read.
 *
 * **Read-only by design.** The append-only invariant depends on nobody but rating
 * writing to `rated_transaction`, and the cheapest way to guarantee that is to make the
 * write methods unreachable from here.
 */
interface ChargeLookup {

    /**
     * Charges billed in [period] for one customer.
     *
     * Superseded rows are excluded: they are history, not the figure anything should be
     * billed from.
     */
    fun findChargesFor(tenant: TenantId, customer: CustomerId, period: BillingPeriod): List<Charge>
}
