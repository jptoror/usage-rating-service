package com.revenium.usage.ingestion.application

import tools.jackson.databind.ObjectMapper
import com.revenium.usage.ingestion.domain.EventConflict
import com.revenium.usage.ingestion.domain.IngestionResult
import com.revenium.usage.ingestion.domain.RawEvent
import com.revenium.usage.ingestion.domain.RawTransactionInput
import com.revenium.usage.ingestion.domain.RejectedEvent
import com.revenium.usage.ingestion.domain.TransactionValidator
import com.revenium.usage.ingestion.domain.UsageTransaction
import com.revenium.usage.ingestion.domain.ValidationFailure
import com.revenium.usage.ingestion.domain.ValidationOutcome
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.ingestion.infrastructure.EventConflictRepository
import com.revenium.usage.ingestion.infrastructure.RawEventRepository
import com.revenium.usage.ingestion.infrastructure.RejectedEventRepository
import com.revenium.usage.ingestion.domain.RatingQueue
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
 * ### Transaction boundary
 *
 * Ingestion is one short `REQUIRED` transaction at `READ_COMMITTED` covering exactly two
 * writes: the `raw_event` and its `outbox_message`. They commit together or not at all,
 * which is the guarantee that makes the outbox pattern work — there is no window where
 * an event is accepted but its rating work is lost.
 *
 * Rating is deliberately **outside** this boundary. The request returns as soon as the
 * event is durable, and a worker picks the message up afterwards. Because the worker
 * queries a committed table rather than being handed an in-memory reference, it cannot
 * observe a row before its transaction commits — a failure mode that `@Async` inside a
 * transactional method runs into constantly.
 *
 * ### Idempotency
 *
 * Duplicate detection is an INSERT that catches the unique-constraint violation, not a
 * SELECT followed by an INSERT. A read-then-write has a race window that concurrent
 * delivery of the same event will find, and at that point both callers believe they are
 * first. The database arbitrates instead.
 *
 * ### Why the writes live in their own beans
 *
 * A constraint violation does two things that catching the exception does not undo:
 *
 * 1. It marks the surrounding transaction **rollback-only**. Catching the exception and
 *    carrying on leads to `UnexpectedRollbackException` at commit -- the work is lost
 *    anyway, just later and more confusingly.
 * 2. It leaves the Hibernate session unusable. The rejected entity stays in the
 *    persistence context with a null identifier and poisons the next flush.
 *
 * So the insert happens in [EventRecorder]'s own transaction. When it fails, that
 * transaction is already rolled back and gone, and [DuplicateResolver] runs on a clean
 * transaction and a clean session. `IngestionService` itself opens no transaction: it
 * decides which boundary to enter, and the outcome of a duplicate is a normal result
 * rather than a poisoned unit of work.
 */
@Service
@RequiresTenant
class IngestionService(
    private val validator: TransactionValidator,
    private val eventRecorder: EventRecorder,
    private val duplicateResolver: DuplicateResolver,
    private val rejectionRecorder: RejectionRecorder,
    private val clock: Clock,
) {

    fun ingest(input: RawTransactionInput): IngestionResult {
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
 * Records an accepted event and queues its rating work, in one transaction.
 *
 * `REQUIRES_NEW` rather than `REQUIRED`: the caller must be able to survive a unique
 * violation here. With a joined transaction the violation would mark the caller's
 * transaction rollback-only, and the duplicate -- an expected, benign outcome -- would
 * fail the whole request at commit time.
 *
 * The two writes share this boundary deliberately. Committing them together is the
 * guarantee the outbox pattern rests on: there is no window in which an event is
 * accepted but its rating work is lost.
 */
@Service
class EventRecorder(
    private val rawEvents: RawEventRepository,
    private val ratingQueue: RatingQueue,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    fun record(tenant: TenantId, transaction: UsageTransaction): IngestionResult {
        val entity = RawEvent(
            tenantId = tenant.value,
            eventId = transaction.eventId.value,
            customerId = transaction.customerId.value,
            transactionCode = transaction.transactionCode.value,
            occurredAt = transaction.occurredAt,
            quantity = transaction.quantity.value,
            payload = transaction.rawPayload,
            payloadHash = transaction.payloadHash,
            receivedAt = clock.instant(),
        )

        // saveAndFlush, not save: the constraint violation must surface here, inside
        // this transaction, rather than at commit time where the caller could no longer
        // distinguish a duplicate from a genuine failure.
        val saved = rawEvents.saveAndFlush(entity)
        ratingQueue.enqueue(tenant, saved.id)

        log.debug { "Accepted event ${transaction.eventId} for tenant $tenant" }
        return IngestionResult.Accepted(transaction.eventId, saved.id, saved.receivedAt)
    }
}

/**
 * Resolves a duplicate in its own transaction and its own Hibernate session.
 *
 * A separate bean because `REQUIRES_NEW` is proxy-based, and because the caller's
 * session is unusable once a constraint violation has occurred: the rejected entity
 * remains in the persistence context with a null identifier, and the next flush fails
 * with `AssertionFailure` rather than doing anything useful.
 *
 * `REQUIRES_NEW` also means the conflict record survives even if the outer transaction
 * is later rolled back -- the discrepancy happened, and the evidence of it should not
 * disappear with the attempt that found it.
 */
@Service
class DuplicateResolver(
    private val rawEvents: RawEventRepository,
    private val conflicts: EventConflictRepository,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resolve(tenant: TenantId, transaction: UsageTransaction): IngestionResult {
        val existing = rawEvents.findByTenantIdAndEventId(tenant.value, transaction.eventId.value)
            ?: throw IllegalStateException(
                "Unique violation for event ${transaction.eventId} but no existing row found"
            )

        // Counted even when the bodies match: an identical re-delivery writes nothing
        // else, so without this tally it would be invisible to reconciliation.
        existing.recordDuplicateDelivery(clock.instant())
        rawEvents.save(existing)

        val payloadDiffers = existing.payloadHash != transaction.payloadHash
        if (payloadDiffers) {
            // Same id, different body: an upstream bug. The first delivery wins so we
            // never double-bill, and the discrepancy is recorded for a human.
            log.warn {
                "Event ${transaction.eventId} re-delivered with a different payload " +
                    "for tenant $tenant; keeping the original"
            }
            conflicts.save(
                EventConflict(
                    tenantId = tenant.value,
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
 * Records rejections in their own transaction.
 *
 * A separate bean because `REQUIRES_NEW` is proxy-based: calling a `REQUIRES_NEW` method
 * on `this` would not start a new transaction at all, and the record would quietly join
 * the caller's — the exact bug this class exists to avoid.
 *
 * The independent transaction is what lets the evidence survive a rollback of the
 * ingestion attempt. Otherwise the record of a rejection would roll back together with
 * the thing it is evidence of, and reconciliation could not account for what arrived.
 */
@Service
class RejectionRecorder(
    private val rejectedEvents: RejectedEventRepository,
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
        rejectedEvents.save(
            RejectedEvent(
                tenantId = tenant.value,
                eventId = eventId?.value,
                customerId = input.customerId?.take(200),
                transactionCode = input.transactionCode?.take(100),
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
