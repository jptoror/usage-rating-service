package com.revenium.usage.processing.domain

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxMessageTest {

    private val now = Instant.parse("2026-09-16T12:00:00Z")
    private val backoff = Duration.ofSeconds(10)

    private fun message() = OutboxMessage(tenantId = "tenant-a", rawEventId = 1L)

    @Test
    fun `starts claimable`() {
        val message = message()
        assertEquals(OutboxStatus.PENDING, message.status)
        assertEquals(0, message.attemptCount)
    }

    // --- success -----------------------------------------------------------

    @Test
    fun `marking done clears the last error`() {
        val message = message()
        message.markFailed(now, "transient failure", maxAttempts = 5, backoffBase = backoff)

        message.markDone(now)

        assertEquals(OutboxStatus.DONE, message.status)
        // A stale error on a successful row would mislead anyone reading the queue.
        assertNull(message.lastError)
    }

    // --- retries -----------------------------------------------------------

    @Test
    fun `a failure schedules a retry and counts the attempt`() {
        val message = message()

        message.markFailed(now, "boom", maxAttempts = 5, backoffBase = backoff)

        assertEquals(OutboxStatus.PENDING, message.status)
        assertEquals(1, message.attemptCount)
        assertEquals(now.plusSeconds(10), message.nextAttemptAt)
    }

    @Test
    fun `backoff doubles with each attempt`() {
        // Exponential, not linear: a dependency that is down stays down for a while,
        // and retrying every 10s adds load without adding a chance of success.
        val message = message()

        message.markFailed(now, "boom", maxAttempts = 10, backoffBase = backoff)
        assertEquals(now.plusSeconds(10), message.nextAttemptAt)

        message.markFailed(now, "boom", maxAttempts = 10, backoffBase = backoff)
        assertEquals(now.plusSeconds(20), message.nextAttemptAt)

        message.markFailed(now, "boom", maxAttempts = 10, backoffBase = backoff)
        assertEquals(now.plusSeconds(40), message.nextAttemptAt)
    }

    @Test
    fun `dead-letters once the attempt budget is exhausted`() {
        val message = message()

        repeat(3) { message.markFailed(now, "boom", maxAttempts = 3, backoffBase = backoff) }

        // FAILED, not deleted: a poison message stays inspectable, and reconciliation
        // can account for it.
        assertEquals(OutboxStatus.FAILED, message.status)
        assertEquals(3, message.attemptCount)
        assertEquals("boom", message.lastError)
    }

    @Test
    fun `retries right up to the final attempt`() {
        // Boundary: with maxAttempts = 3, the second failure must still retry.
        val message = message()

        message.markFailed(now, "boom", maxAttempts = 3, backoffBase = backoff)
        assertEquals(OutboxStatus.PENDING, message.status)

        message.markFailed(now, "boom", maxAttempts = 3, backoffBase = backoff)
        assertEquals(OutboxStatus.PENDING, message.status)

        message.markFailed(now, "boom", maxAttempts = 3, backoffBase = backoff)
        assertEquals(OutboxStatus.FAILED, message.status)
    }

    @Test
    fun `truncates an oversized error rather than failing to record it`() {
        val message = message()

        message.markFailed(now, "x".repeat(5000), maxAttempts = 5, backoffBase = backoff)

        assertTrue((message.lastError?.length ?: 0) <= 2000)
    }

    // --- unrated -----------------------------------------------------------

    @Test
    fun `a missing pricing rule does not count against the attempt budget`() {
        // The distinction that keeps good usage from being dead-lettered: a missing
        // rule is a configuration gap, not a defective event. The rule may be created
        // tomorrow, and the event must still be waiting when it is.
        val message = message()

        repeat(10) { message.markUnrated(now, Duration.ofMinutes(5)) }

        assertEquals(OutboxStatus.UNRATED, message.status)
        assertEquals(0, message.attemptCount)
    }

    @Test
    fun `an unrated message retries on its own slower cadence`() {
        val message = message()

        message.markUnrated(now, Duration.ofMinutes(5))

        assertEquals(now.plus(Duration.ofMinutes(5)), message.nextAttemptAt)
        assertTrue(message.lastError!!.contains("pricing rule"))
    }

    // --- quarantine --------------------------------------------------------

    @Test
    fun `quarantine holds a message without billing or discarding it`() {
        val message = message()

        message.markQuarantined(now, "occurredAt is 400 days old")

        // Terminal but not a failure: a human decides, because an event this old is
        // usually an accidental replay and billing it automatically would be worse
        // than stopping.
        assertEquals(OutboxStatus.QUARANTINED, message.status)
        assertEquals("occurredAt is 400 days old", message.lastError)
    }

    @Test
    fun `every transition stamps updatedAt`() {
        val later = now.plusSeconds(3600)

        message().let { it.markDone(later); assertEquals(later, it.updatedAt) }
        message().let { it.markFailed(later, "e", 5, backoff); assertEquals(later, it.updatedAt) }
        message().let { it.markUnrated(later, backoff); assertEquals(later, it.updatedAt) }
        message().let { it.markQuarantined(later, "r"); assertEquals(later, it.updatedAt) }
    }
}
