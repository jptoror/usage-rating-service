package com.revenium.usage.processing.application

import com.revenium.usage.processing.domain.OutboxMessage
import com.revenium.usage.processing.infrastructure.ClaimedWork
import com.revenium.usage.processing.infrastructure.OutboxClaimRepository
import com.revenium.usage.processing.infrastructure.OutboxMessageRepository
import com.revenium.usage.rating.application.ConcurrentRatingException
import com.revenium.usage.rating.application.RatingService
import com.revenium.usage.rating.domain.RatingOutcome
import com.revenium.usage.tenancy.TenantContext
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
    val pollInterval: Duration = Duration.ofSeconds(1),
    val batchSize: Int = 50,
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
 * ### Multi-instance coordination
 *
 * Any number of instances may run this concurrently. Coordination is entirely in
 * PostgreSQL, through `FOR UPDATE SKIP LOCKED` in the claim query: each worker takes a
 * disjoint batch, with no leader election, no distributed lock and no broker.
 *
 * ### Tenant propagation
 *
 * The claim runs **unscoped**, because work has to be discovered across tenants, and a
 * narrow RLS policy permits exactly that and nothing else — an unscoped connection can
 * see queue rows and read events, but cannot write a charge.
 *
 * Every message is then processed inside `TenantContext.runAs(...)` using the tenant
 * recorded on its own row. Nothing is inherited from the polling thread: a `ThreadLocal`
 * does not cross a thread boundary, and a tenant inherited by accident would be far
 * worse than none.
 *
 * ### Failure handling
 *
 * Each message is rated in its own transaction, so one failure never rolls back the
 * batch. Outcomes:
 *
 * - **Rated / AlreadyRated** → `DONE`.
 * - **Unrated** → retried on a slow cadence and **not** counted as an attempt: a missing
 *   pricing rule is a configuration gap, and the rule may be created tomorrow.
 * - **Quarantined** → held for a human. Terminal, but not a failure.
 * - **Exception** → retried with exponential backoff, then dead-lettered as `FAILED`.
 *   Nothing is ever discarded.
 */
@Component
class OutboxWorker(
    private val claims: OutboxClaimRepository,
    private val messages: OutboxMessageRepository,
    private val ratingService: RatingService,
    private val status: OutboxStatusRecorder,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {

    /**
     * One polling cycle. Returns how many messages were processed, which the tests use
     * to drive the worker deterministically instead of waiting on the scheduler.
     */
    fun pollOnce(): Int {
        val now = clock.instant()
        claims.reclaimStale(now, properties.staleClaimTimeout)

        val batch = claims.claimBatch(now, properties.batchSize)
        if (batch.isEmpty()) return 0

        log.debug { "Claimed ${batch.size} outbox messages" }
        batch.forEach(::process)
        return batch.size
    }

    private fun process(work: ClaimedWork) {
        // Explicit, per-message tenant scope, taken from the claimed row -- never
        // inherited from whatever this pool thread did previously.
        TenantContext.runAs(TenantId(work.tenantId)) {
            try {
                when (val outcome = ratingService.rate(work)) {
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

    private fun markDone(work: ClaimedWork) = status.markDone(work)

    private fun markUnrated(work: ClaimedWork) = status.markUnrated(work)

    private fun markQuarantined(work: ClaimedWork, reason: String) = status.markQuarantined(work, reason)

    private fun markFailed(work: ClaimedWork, error: Exception) = status.markFailed(work, error)
}

/**
 * Records the outcome of processing a message, each write in its own transaction.
 *
 * A separate bean, not private methods on the worker. `@Transactional` is proxy-based,
 * so calling these through `this` from inside `OutboxWorker` would bypass the proxy
 * entirely and the `REQUIRES_NEW` would silently do nothing — the same self-invocation
 * limitation `TenantGuardAspect` documents, and just as easy to write by accident.
 *
 * The separate transaction matters here: the rating transaction has already committed or
 * rolled back by the time this runs, and the outcome must be durable either way. A status
 * update that rolled back with a failed rating would leave the message stuck in
 * `PROCESSING` with nothing recording why.
 */
@Component
class OutboxStatusRecorder(
    private val messages: OutboxMessageRepository,
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

    private fun update(work: ClaimedWork, mutate: (OutboxMessage) -> Unit) {
        val message = messages.findByTenantIdAndRawEventId(work.tenantId, work.rawEventId)
        if (message == null) {
            log.warn { "Outbox message for event ${work.rawEventId} vanished before its status was recorded" }
            return
        }
        mutate(message)
        messages.save(message)
    }
}
