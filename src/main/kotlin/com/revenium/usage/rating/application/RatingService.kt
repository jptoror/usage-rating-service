package com.revenium.usage.rating.application

import com.revenium.usage.invoicing.domain.port.out.BillingPeriodStatusLookup
import com.revenium.usage.rating.domain.model.RateableTransaction
import com.revenium.usage.rating.domain.model.RatedTransaction
import com.revenium.usage.rating.domain.model.RatingCalculator
import com.revenium.usage.rating.domain.model.RatingOutcome
import com.revenium.usage.rating.domain.model.RatingRequest
import com.revenium.usage.rating.domain.port.`in`.RateTransactionUseCase
import com.revenium.usage.rating.domain.port.out.RatedTransactionStore
import com.revenium.usage.tenancy.RequiresTenant
import com.revenium.usage.tenancy.TenantContext
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private val log = KotlinLogging.logger {}

/**
 * Rates one claimed event and persists the result.
 *
 * `REQUIRES_NEW` per message, so one poison message cannot roll back a whole batch.
 *
 * At-least-once delivery means this can run twice for the same event, concurrently.
 * Neither produces a second charge: the partial unique index on `rated_transaction` is
 * the guarantee, and the check below is only a fast path.
 */
@Service
@RequiresTenant
class RatingService(
    private val calculator: RatingCalculator,
    private val ratedTransactions: RatedTransactionStore,
    private val billingPeriods: BillingPeriodStatusLookup,
    private val clock: Clock,
) : RateTransactionUseCase {

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    override fun rate(transaction: RateableTransaction): RatingOutcome {
        val tenant = TenantContext.current()
        require(tenant == transaction.tenantId) {
            "Rating ${transaction.rawEventId} for ${transaction.tenantId} while acting as $tenant"
        }

        ratedTransactions.findCurrent(tenant, transaction.rawEventId)?.let { existing ->
            log.debug { "Event ${transaction.rawEventId} is already rated; skipping" }
            return RatingOutcome.AlreadyRated(existing.id)
        }

        val outcome = calculator.rate(
            request = RatingRequest(
                tenant = tenant,
                transactionCode = transaction.transactionCode,
                quantity = transaction.quantity,
                occurredAt = transaction.occurredAt,
                receivedAt = transaction.receivedAt,
            ),
            isPeriodClosed = { period ->
                billingPeriods.isClosed(tenant, transaction.customerId, period)
            },
            now = clock.instant(),
        )

        if (outcome is RatingOutcome.Rated) {
            persist(transaction, outcome)
        }
        return outcome
    }

    private fun persist(transaction: RateableTransaction, rated: RatingOutcome.Rated) {
        val row = RatedTransaction(
            tenantId = transaction.tenantId,
            rawEventId = transaction.rawEventId,
            customerId = transaction.customerId,
            transactionCode = transaction.transactionCode,
            // Both the rule's identity and its value at the time: the row must keep
            // explaining its own amount after the rule is corrected.
            pricingRuleId = rated.rule.id,
            unitPrice = rated.rule.unitPrice,
            quantity = transaction.quantity,
            amount = rated.amount,
            occurredAt = transaction.occurredAt,
            billingPeriod = rated.billingPeriod,
            originPeriod = rated.originPeriod,
            isLateAdjustment = rated.isLateAdjustment,
            ratedAt = clock.instant(),
        )

        try {
            ratedTransactions.save(row)
        } catch (e: DataIntegrityViolationException) {
            // Only a violation of the current-rating index means another worker won the
            // race. Any other integrity failure is a defect: reporting one as a lost race
            // once made the worker mark the message DONE and lose the charge silently.
            if (isDuplicateRatingViolation(e)) {
                log.debug(e) { "Concurrent rating of event ${transaction.rawEventId}" }
                throw ConcurrentRatingException(transaction.rawEventId, e)
            }
            log.error(e) { "Rating of event ${transaction.rawEventId} violated a database invariant" }
            throw e
        }
    }

    private fun isDuplicateRatingViolation(e: DataIntegrityViolationException): Boolean =
        generateSequence(e as Throwable) { it.cause }
            .mapNotNull { it.message }
            .any { it.contains(CURRENT_RATING_INDEX, ignoreCase = true) }

    private companion object {
        /** Mirrors the index created in changeset 004-02. */
        const val CURRENT_RATING_INDEX = "uq_rated_transaction_current"
    }
}

/**
 * Two workers rated the same event and this one lost.
 *
 * Not a failure to retry: the event *is* rated, just not by this worker.
 */
class ConcurrentRatingException(val rawEventId: Long, cause: Throwable) :
    RuntimeException("Event $rawEventId was rated concurrently by another worker", cause)
