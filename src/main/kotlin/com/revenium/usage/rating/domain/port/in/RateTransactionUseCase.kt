package com.revenium.usage.rating.domain.port.`in`

import com.revenium.usage.rating.domain.model.RateableTransaction
import com.revenium.usage.rating.domain.model.RatingOutcome

/**
 * Rates one accepted event.
 *
 * An inbound port: what drives rating — today the outbox worker, tomorrow a replay tool —
 * depends on this rather than on the service class.
 */
interface RateTransactionUseCase {
    fun rate(transaction: RateableTransaction): RatingOutcome
}
