package com.revenium.usage.processing.application

import com.revenium.usage.processing.domain.port.`in`.DrainOutboxUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

/**
 * Drives [OutboxWorker] on a fixed cadence.
 *
 * Separate from the worker so the worker can be driven directly in tests, without
 * waiting on a scheduler or sleeping -- a test that sleeps is a test that is either slow
 * or flaky, usually both.
 *
 * Disabled entirely in integration tests via `outbox.scheduler-enabled=false`. Leaving it
 * on meant it rated events in the background while a test was asserting on a known state,
 * and while the cleaner was deleting between tests -- a race that produced foreign-key
 * failures with no relationship to the test that reported them.
 *
 * ### Graceful shutdown
 *
 * On SIGTERM the [shutdown] hook stops new cycles from starting, while the cycle already
 * in flight finishes its batch. Anything not yet claimed stays PENDING, and anything
 * claimed but unfinished is reclaimed by another instance after the stale-claim timeout.
 * Nothing is lost either way; the shutdown just avoids leaving work in limbo.
 */
@Component
@ConditionalOnProperty(name = ["outbox.scheduler-enabled"], havingValue = "true", matchIfMissing = true)
class OutboxScheduler(private val worker: DrainOutboxUseCase) {

    private val running = AtomicBoolean(true)

    @Scheduled(fixedDelayString = "\${outbox.poll-interval:1000ms}")
    fun poll() {
        if (!running.get()) return

        try {
            worker.pollOnce()
        } catch (e: Exception) {
            // Never let an exception escape a scheduled method: Spring would cancel the
            // schedule and the queue would silently stop draining.
            log.error(e) { "Outbox polling cycle failed; will retry on the next tick" }
        }
    }

    @PreDestroy
    fun shutdown() {
        log.info { "Shutting down: no further outbox cycles will start" }
        running.set(false)
    }
}
