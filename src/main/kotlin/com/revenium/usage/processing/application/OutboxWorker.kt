package com.revenium.usage.processing.application

import com.revenium.usage.processing.domain.model.InstanceId
import com.revenium.usage.processing.domain.model.OutboxMessage
import com.revenium.usage.processing.domain.port.`in`.DrainOutboxUseCase
import com.revenium.usage.processing.domain.port.out.OutboxMessageStore
import com.revenium.usage.processing.infrastructure.ClaimedWork
import com.revenium.usage.processing.infrastructure.OutboxClaimRepository
import com.revenium.usage.rating.application.ConcurrentRatingException
import com.revenium.usage.rating.application.RatingService
import com.revenium.usage.rating.domain.model.RateableTransaction
import com.revenium.usage.rating.domain.model.RatingOutcome
import com.revenium.usage.tenancy.TenantContext
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

@ConfigurationProperties("outbox")
data class OutboxProperties(
    /**
     * How often a worker asks for work. This, not per-message cost, bounds throughput:
     * `batchSize / pollInterval` is a hard ceiling, 50 events/s at the original 1s.
     * See docs/analysis/performance.md.
     */
    val pollInterval: Duration = Duration.ofMillis(200),
    val batchSize: Int = 200,
    val maxAttempts: Int = 5,
    val backoffBase: Duration = Duration.ofSeconds(10),
    /** Missing pricing rules retry on this slower cadence, indefinitely. */
    val unratedRetryDelay: Duration = Duration.ofMinutes(5),
    /** How long a `PROCESSING` row may sit before it is assumed abandoned. */
    val staleClaimTimeout: Duration = Duration.ofMinutes(10),
)

/**
 * Drains the outbox: claims work, rates it, records the outcome.
 *
 * Any number of instances may run this concurrently; coordination is entirely
 * `FOR UPDATE SKIP LOCKED` in the claim query.
 *
 * The claim runs unscoped because work must be discovered across tenants — a narrow RLS policy
 * permits exactly that and nothing else — and each message is then processed inside
 * `TenantContext.runAs(...)` with the tenant from its own row, in its own transaction so one
 * failure never rolls back a batch.
 */
@Component
class OutboxWorker(
    private val claims: OutboxClaimRepository,
    private val ratingService: RatingService,
    private val status: OutboxStatusRecorder,
    private val properties: OutboxProperties,
    private val clock: Clock,
    // Injected rather than defaulted: a default on a Spring-wired constructor makes Kotlin
    // emit a synthetic DefaultConstructorMarker parameter Spring fails to resolve at startup.
    private val instanceId: InstanceId,
) : DrainOutboxUseCase {

    /** One polling cycle, returning the count so tests can drive it without the scheduler. */
    override fun pollOnce(): Int {
        val now = clock.instant()
        claims.reclaimStale(now, properties.staleClaimTimeout)

        val batch = claims.claimBatch(now, properties.batchSize, instanceId.value)
        if (batch.isEmpty()) return 0

        log.debug { "Claimed ${batch.size} outbox messages" }
        batch.forEach(::process)
        return batch.size
    }

    private fun process(work: ClaimedWork) {
        // Tenant taken from the claimed row, never inherited from this pool thread's last use.
        TenantContext.runAs(TenantId(work.tenantId)) {
            try {
                when (val outcome = ratingService.rate(work.toRateable())) {
                    is RatingOutcome.Rated -> {
                        markDone(work)
                        log.debug {
                            "Rated event ${work.rawEventId} at ${outcome.amount} " +
                                "(period ${outcome.billingPeriod}, late=${outcome.isLateAdjustment})"
                        }
                    }

                    is RatingOutcome.AlreadyRated -> markDone(work)

                    is RatingOutcome.Unrated -> {
                        markUnrated(work)
                        log.info { "Event ${work.rawEventId} is unrated: ${outcome.reason}" }
                    }

                    is RatingOutcome.Quarantined -> {
                        markQuarantined(work, outcome.reason)
                        log.warn { "Quarantined event ${work.rawEventId}: ${outcome.reason}" }
                    }
                }
            } catch (e: ConcurrentRatingException) {
                // Another worker rated it first. The work is done, just not by us.
                markDone(work)
            } catch (e: Exception) {
                log.warn(e) { "Failed to rate event ${work.rawEventId}" }
                markFailed(work, e)
            }
        }
    }

    /** Mapped here, in the consumer, so rating stays independent of how work was discovered. */
    private fun ClaimedWork.toRateable() = RateableTransaction(
        tenantId = TenantId(tenantId),
        rawEventId = rawEventId,
        customerId = CustomerId(customerId),
        transactionCode = TransactionCode(transactionCode),
        occurredAt = occurredAt,
        receivedAt = receivedAt,
        quantity = Quantity(quantity),
    )

    private fun markDone(work: ClaimedWork) = status.markDone(work)

    private fun markUnrated(work: ClaimedWork) = status.markUnrated(work)

    private fun markQuarantined(work: ClaimedWork, reason: String) = status.markQuarantined(work, reason)

    private fun markFailed(work: ClaimedWork, error: Exception) = status.markFailed(work, error)
}

/**
 * Records the outcome of processing a message, each write in its own transaction: the outcome
 * must be durable whether or not the rating transaction rolled back, or a failed rating leaves
 * the message stuck in `PROCESSING` with nothing recording why.
 *
 * A separate bean because `@Transactional` is proxy-based: self-invocation from `OutboxWorker`
 * would make `REQUIRES_NEW` silently do nothing.
 */
@Component
class OutboxStatusRecorder(
    private val messages: OutboxMessageStore,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markDone(work: ClaimedWork) = update(work) { it.markDone(clock.instant()) }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markUnrated(work: ClaimedWork) =
        update(work) { it.markUnrated(clock.instant(), properties.unratedRetryDelay) }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markQuarantined(work: ClaimedWork, reason: String) =
        update(work) { it.markQuarantined(clock.instant(), reason) }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailed(work: ClaimedWork, error: Exception) = update(work) {
        it.markFailed(
            now = clock.instant(),
            error = error.message ?: error::class.simpleName.orEmpty(),
            maxAttempts = properties.maxAttempts,
            backoffBase = properties.backoffBase,
        )
    }

    private fun update(work: ClaimedWork, transition: (OutboxMessage) -> OutboxMessage) {
        val message = messages.findByRawEventId(TenantId(work.tenantId), work.rawEventId)
        if (message == null) {
            log.warn { "Outbox message for event ${work.rawEventId} vanished before its status was recorded" }
            return
        }
        messages.save(transition(message))
    }
}
