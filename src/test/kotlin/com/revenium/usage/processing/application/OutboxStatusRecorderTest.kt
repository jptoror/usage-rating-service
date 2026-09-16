package com.revenium.usage.processing.application

import com.revenium.usage.processing.domain.OutboxMessage
import com.revenium.usage.processing.domain.OutboxStatus
import com.revenium.usage.processing.infrastructure.ClaimedWork
import com.revenium.usage.processing.infrastructure.OutboxMessageRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutboxStatusRecorderTest {

    private val now = Instant.parse("2026-09-16T12:00:00Z")
    private val messages = mockk<OutboxMessageRepository> {
        every { save(any()) } answers { firstArg() }
    }
    private val properties = OutboxProperties(maxAttempts = 3, backoffBase = Duration.ofSeconds(10))
    private val recorder = OutboxStatusRecorder(messages, properties, Clock.fixed(now, ZoneOffset.UTC))

    private val work = ClaimedWork(
        outboxId = 1L,
        tenantId = "tenant-a",
        rawEventId = 1L,
        customerId = "customer-42",
        transactionCode = "CODE",
        occurredAt = now,
        receivedAt = now,
        quantity = BigDecimal.ONE,
        attemptCount = 0,
    )

    private fun existing(message: OutboxMessage = OutboxMessage(tenantId = "tenant-a", rawEventId = 1L)) =
        message.also { every { messages.findByTenantIdAndRawEventId("tenant-a", 1L) } returns it }

    @Test
    fun `marks a message done`() {
        val message = existing()
        recorder.markDone(work)

        assertEquals(OutboxStatus.DONE, message.status)
        verify { messages.save(message) }
    }

    @Test
    fun `marks a message unrated without counting an attempt`() {
        val message = existing()
        recorder.markUnrated(work)

        assertEquals(OutboxStatus.UNRATED, message.status)
        assertEquals(0, message.attemptCount)
        assertEquals(now.plus(properties.unratedRetryDelay), message.nextAttemptAt)
    }

    @Test
    fun `quarantines with the reason attached`() {
        val message = existing()
        recorder.markQuarantined(work, "too old")

        assertEquals(OutboxStatus.QUARANTINED, message.status)
        assertEquals("too old", message.lastError)
    }

    @Test
    fun `records a failure for retry`() {
        val message = existing()
        recorder.markFailed(work, RuntimeException("boom"))

        assertEquals(OutboxStatus.PENDING, message.status)
        assertEquals(1, message.attemptCount)
        assertEquals("boom", message.lastError)
    }

    @Test
    fun `dead-letters once attempts are exhausted`() {
        val message = existing()
        repeat(properties.maxAttempts) { recorder.markFailed(work, RuntimeException("boom")) }

        assertEquals(OutboxStatus.FAILED, message.status)
    }

    @Test
    fun `falls back to the exception type when there is no message`() {
        val message = existing()
        recorder.markFailed(work, NullPointerException())

        assertEquals("NullPointerException", message.lastError)
    }

    @Test
    fun `does nothing when the message has vanished`() {
        // Not a crash: a missing row means something else already handled it, and
        // throwing here would fail a batch over bookkeeping.
        every { messages.findByTenantIdAndRawEventId(any(), any()) } returns null

        recorder.markDone(work)

        verify(exactly = 0) { messages.save(any()) }
    }
}
