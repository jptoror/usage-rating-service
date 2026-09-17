package com.revenium.usage.invoicing.domain.model

import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode

/**
 * One charge, as invoicing needs to read it.
 *
 * A projection, not rating's model: invoicing groups and sums these and has no business
 * seeing the correction chain or the rating timestamps.
 */
data class Charge(
    val transactionCode: TransactionCode,
    val quantity: Quantity,
    /** Already rounded when the transaction was rated. Summed here, never recalculated. */
    val amount: Money,
    val originPeriod: BillingPeriod,
    val isLateAdjustment: Boolean,
)
