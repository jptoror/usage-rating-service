package com.revenium.usage.rating.application

import com.revenium.usage.invoicing.domain.BillingPeriodStatusLookup
import com.revenium.usage.processing.infrastructure.ClaimedWork
import com.revenium.usage.rating.domain.RatedTransaction
import com.revenium.usage.rating.domain.RatingCalculator
import com.revenium.usage.rating.domain.RatingOutcome
import com.revenium.usage.rating.domain.RatingRequest
import com.revenium.usage.rating.infrastructure.RatedTransactionRepository
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.RequiresTenant
import com.revenium.usage.tenancy.TenantContext
import com.revenium.usage.tenancy.TenantId
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
 * ### Transaction boundary
 *
 * `REQUIRES_NEW` at `READ_COMMITTED`, one transaction per message. Deliberately not one
 * transaction for the whole batch: a single poison message would then roll back every
 * good message claimed alongside it, and the batch would fail as a unit for ever.
 *
 * ### Idempotency
 *
 * At-least-once delivery means this can run twice for the same event, and two workers
 * can run it concurrently. Neither produces a second charge, because
 * `rated_transaction` carries a partial unique index on `(tenant_id, raw_event_id)
 * WHERE superseded_by IS NULL`. The check below is a fast path; the constraint is what
 * actually guarantees it, and the catch handles the race the check cannot close.
 */
@Service
@RequiresTenant
class RatingService(
    private val calculator: RatingCalculator,
    private val ratedTransactions: RatedTransactionRepository,
    private val billingPeriods: BillingPeriodStatusLookup,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    fun rate(work: ClaimedWork): RatingOutcome {
        val tenant = TenantContext.current()
        require(tenant.value == work.tenantId) {
            "Rating ${work.rawEventId} for ${work.tenantId} while acting as $tenant"
        }

        // Fast path for an obvious redelivery. Not the guarantee -- two workers can pass
        // this check simultaneously -- but it avoids the cost of rating work that is
        // already done.
        ratedTransactions.findCurrentByRawEventId(tenant.value, work.rawEventId)?.let { existing ->
            log.debug { "Event ${work.rawEventId} is already rated; skipping" }
            return alreadyRated(existing)
        }

        val outcome = calculator.rate(
            request = RatingRequest(
                tenant = tenant,
                transactionCode = TransactionCode(work.transactionCode),
                quantity = Quantity(work.quantity),
                occurredAt = work.occurredAt,
                receivedAt = work.receivedAt,
            ),
            isPeriodClosed = { period ->
                billingPeriods.isClosed(tenant, CustomerId(work.customerId), period)
            },
            now = clock.instant(),
        )

        if (outcome is RatingOutcome.Rated) {
            persist(tenant, work, outcome)
        }
        return outcome
    }

    private fun persist(tenant: TenantId, work: ClaimedWork, rated: RatingOutcome.Rated) {
        val row = RatedTransaction(
            tenantId = tenant.value,
            rawEventId = work.rawEventId,
            customerId = work.customerId,
            transactionCode = work.transactionCode,
            pricingRuleId = rated.rule.id,
            // Both the rule's identity and its value at the time: the row must keep
            // explaining its own amount after the rule is corrected.
            unitPrice = rated.rule.unitPrice,
            quantity = work.quantity,
            amount = rated.amount.amount,
            currency = rated.amount.currency.currencyCode,
            occurredAt = work.occurredAt,
            billingPeriod = rated.billingPeriod.startDate,
            originPeriod = rated.originPeriod.startDate,
            isLateAdjustment = rated.isLateAdjustment,
            ratedAt = clock.instant(),
        )

        try {
            ratedTransactions.saveAndFlush(row)
        } catch (e: DataIntegrityViolationException) {
            // Only a UNIQUE violation on the current-rating index means another worker
            // won the race. Every other integrity violation -- a CHECK rejecting an
            // inconsistent row, a foreign key pointing nowhere -- is a real defect.
            //
            // Treating them alike hid a genuine bug: a CHECK violation was reported as a
            // lost race, the worker marked the message DONE, and the charge vanished
            // silently. An event was accepted and never billed, with nothing recording why.
            if (isDuplicateRatingViolation(e)) {
                log.debug(e) { "Concurrent rating of event ${work.rawEventId}; the other worker won" }
                throw ConcurrentRatingException(work.rawEventId, e)
            }

            log.error(e) { "Rating of event ${work.rawEventId} violated a database invariant" }
            throw e
        }
    }

    private fun alreadyRated(existing: RatedTransaction): RatingOutcome.AlreadyRated =
        RatingOutcome.AlreadyRated(existing.id)

    /**
     * Whether this violation is the partial unique index refusing a second current
     * rating -- the one integrity failure that is an expected outcome rather than a bug.
     *
     * Matched on the constraint name, which the database reports in the message chain.
     */
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
 * Not a failure to retry: the event *is* rated, just not by this worker. The message is
 * marked done rather than counted as an attempt.
 */
class ConcurrentRatingException(val rawEventId: Long, cause: Throwable) :
    RuntimeException("Event $rawEventId was rated concurrently by another worker", cause)
