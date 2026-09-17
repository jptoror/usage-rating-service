package com.revenium.usage.ingestion.application

import tools.jackson.databind.ObjectMapper
import com.revenium.usage.ingestion.domain.model.EventConflict
import com.revenium.usage.ingestion.domain.model.IngestionResult
import com.revenium.usage.ingestion.domain.model.RawEvent
import com.revenium.usage.ingestion.domain.model.RawTransactionInput
import com.revenium.usage.ingestion.domain.model.RejectedEvent
import com.revenium.usage.ingestion.domain.model.TransactionValidator
import com.revenium.usage.ingestion.domain.model.UsageTransaction
import com.revenium.usage.ingestion.domain.model.ValidationFailure
import com.revenium.usage.ingestion.domain.model.ValidationOutcome
import com.revenium.usage.ingestion.domain.port.`in`.IngestTransactionUseCase
import com.revenium.usage.ingestion.domain.port.out.EventStore
import com.revenium.usage.ingestion.domain.port.out.RatingQueue
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.EventId
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
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Accepts usage transactions, records them durably, and queues them for rating.
 *
 * Rating happens outside the ingestion transaction: the worker queries a committed table
 * rather than an in-memory reference, so it cannot observe a row before it commits — the
 * failure mode `@Async` inside a transactional method runs into.
 *
 * Duplicate detection is an INSERT that catches the unique violation, not SELECT-then-INSERT:
 * concurrent delivery of the same event finds that race window and both callers believe
 * they are first. The database arbitrates instead.
 *
 * The writes live in their own beans because a constraint violation marks the surrounding
 * transaction rollback-only (yielding `UnexpectedRollbackException` at commit) and leaves
 * the Hibernate session unusable. With the insert in [EventRecorder]'s own transaction,
 * [DuplicateResolver] runs on a clean transaction and a clean session.
 */
@Service
@RequiresTenant
class IngestionService(
    private val validator: TransactionValidator,
    private val eventRecorder: EventRecorder,
    private val duplicateResolver: DuplicateResolver,
    private val rejectionRecorder: RejectionRecorder,
    private val clock: Clock,
) : IngestTransactionUseCase {

    override fun ingest(input: RawTransactionInput): IngestionResult {
        val tenant = TenantContext.current()

        return when (val outcome = validator.validate(input, tenant)) {
            is ValidationOutcome.Invalid -> reject(tenant, input, outcome.failures, outcome.eventId)
            is ValidationOutcome.Valid -> accept(tenant, outcome.transaction)
        }
    }

    private fun accept(tenant: TenantId, transaction: UsageTransaction): IngestionResult =
        try {
            eventRecorder.record(tenant, transaction)
        } catch (e: DataIntegrityViolationException) {
            log.debug(e) { "Duplicate delivery of event ${transaction.eventId} for tenant $tenant" }
            // The failed transaction is already rolled back, so this runs on a clean
            // session and a clean transaction of its own.
            duplicateResolver.resolve(tenant, transaction)
        }

    private fun reject(
        tenant: TenantId,
        input: RawTransactionInput,
        failures: List<ValidationFailure>,
        eventId: EventId?,
    ): IngestionResult {
        rejectionRecorder.record(tenant, input, failures, eventId)
        log.info { "Rejected event ${eventId ?: "<unparseable>"} for tenant $tenant: $failures" }
        return IngestionResult.Rejected(eventId, failures)
    }
}

/**
 * Records an accepted event and queues its rating work in one transaction, so there is no
 * window in which an event is accepted but its rating work is lost.
 *
 * `REQUIRES_NEW`, because a joined transaction would let a unique violation here — an
 * expected, benign duplicate — mark the caller rollback-only and fail the whole request.
 */
@Service
class EventRecorder(
    private val events: EventStore,
    private val ratingQueue: RatingQueue,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    fun record(tenant: TenantId, transaction: UsageTransaction): IngestionResult {
        val event = RawEvent(
            tenantId = tenant,
            eventId = transaction.eventId,
            customerId = transaction.customerId,
            transactionCode = transaction.transactionCode,
            occurredAt = transaction.occurredAt,
            quantity = transaction.quantity,
            payload = transaction.rawPayload,
            payloadHash = transaction.payloadHash,
            receivedAt = clock.instant(),
        )

        // The store flushes, so a duplicate surfaces here rather than at commit time,
        // where the caller could no longer distinguish one from a genuine failure.
        val saved = events.record(event)
        ratingQueue.enqueue(tenant, saved.id)

        log.debug { "Accepted event ${transaction.eventId} for tenant $tenant" }
        return IngestionResult.Accepted(transaction.eventId, saved.id, saved.receivedAt)
    }
}

/**
 * Resolves a duplicate in its own transaction and its own Hibernate session.
 *
 * A separate bean because `REQUIRES_NEW` is proxy-based, and because a constraint violation
 * leaves the caller's session unusable: the rejected entity stays in the persistence context
 * with a null identifier and the next flush fails with `AssertionFailure`. The independent
 * transaction also keeps the conflict record if the outer attempt is rolled back.
 */
@Service
class DuplicateResolver(
    private val events: EventStore,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resolve(tenant: TenantId, transaction: UsageTransaction): IngestionResult {
        val existing = events.findByEventId(tenant, transaction.eventId)
            ?: throw IllegalStateException(
                "Unique violation for event ${transaction.eventId} but no existing row found"
            )

        // Counted even when the bodies match: an identical re-delivery writes nothing
        // else, so without this tally it would be invisible to reconciliation.
        events.recordDuplicateDelivery(existing.recordDuplicateDelivery(clock.instant()))

        val payloadDiffers = existing.payloadHash != transaction.payloadHash
        if (payloadDiffers) {
            // Same id, different body: an upstream bug. The first delivery wins so we
            // never double-bill, and the discrepancy is recorded for a human.
            log.warn {
                "Event ${transaction.eventId} re-delivered with a different payload " +
                    "for tenant $tenant; keeping the original"
            }
            events.recordConflict(
                EventConflict(
                    tenantId = tenant,
                    rawEventId = existing.id,
                    originalPayloadHash = existing.payloadHash,
                    conflictingPayloadHash = transaction.payloadHash,
                    conflictingPayload = transaction.rawPayload,
                    detectedAt = clock.instant(),
                )
            )
        }

        return IngestionResult.Duplicate(
            eventId = transaction.eventId,
            rawEventId = existing.id,
            originalReceivedAt = existing.receivedAt,
            conflictingPayload = payloadDiffers,
        )
    }
}

/**
 * Records rejections in their own transaction, so the evidence survives a rollback of the
 * ingestion attempt it is evidence of.
 *
 * A separate bean because `REQUIRES_NEW` is proxy-based: self-invocation would quietly join
 * the caller's transaction instead.
 */
@Service
class RejectionRecorder(
    private val events: EventStore,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        tenant: TenantId,
        input: RawTransactionInput,
        failures: List<ValidationFailure>,
        eventId: EventId?,
    ) {
        events.recordRejection(
            RejectedEvent(
                tenantId = tenant,
                eventId = eventId,
                customerId = input.customerId?.trim()?.take(CustomerId.MAX_LENGTH)
                    ?.takeIf { it.isNotBlank() }?.let(::CustomerId),
                transactionCode = input.transactionCode?.trim()?.take(TransactionCode.MAX_LENGTH)
                    ?.takeIf { it.isNotBlank() }?.let(::TransactionCode),
                occurredAt = runCatching { input.occurredAt?.let(Instant::parse) }.getOrNull(),
                rejectionReasons = objectMapper.writeValueAsString(
                    failures.map { mapOf("field" to it.field, "reason" to it.reason) }
                ),
                payload = input.rawPayload,
                receivedAt = clock.instant(),
            )
        )
    }
}
