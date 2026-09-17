package com.revenium.usage.processing.application

import com.revenium.usage.processing.domain.model.OutboxMessage
import com.revenium.usage.processing.domain.model.OutboxStatus
import com.revenium.usage.processing.infrastructure.ClaimedWork
import com.revenium.usage.processing.domain.port.out.OutboxMessageStore
import com.revenium.usage.tenancy.TenantId
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
    private val saved = mutableListOf<OutboxMessage>()
    private val messages = mockk<OutboxMessageStore> {
        every { save(any()) } answers { firstArg<OutboxMessage>().also(saved::add) }
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

    /** Stages the message the recorder will read back, and returns what it then saved. */
    private fun existing(
        message: OutboxMessage = OutboxMessage(tenantId = TenantId("tenant-a"), rawEventId = 1L),
    ) {
        // The store answers with whatever the previous transition saved, so a repeated
        // call accumulates attempts exactly as it does against the database.
        every { messages.findByRawEventId(TenantId("tenant-a"), 1L) } answers {
            saved.lastOrNull() ?: message
        }
    }

    private fun lastSaved(): OutboxMessage = saved.last()

    @Test
    fun `marks a message done`() {
        existing()
        recorder.markDone(work)

        assertEquals(OutboxStatus.DONE, lastSaved().status)
        verify { messages.save(any()) }
    }

    @Test
    fun `marks a message unrated without counting an attempt`() {
        existing()
        recorder.markUnrated(work)

        assertEquals(OutboxStatus.UNRATED, lastSaved().status)
        assertEquals(0, lastSaved().attemptCount)
        assertEquals(now.plus(properties.unratedRetryDelay), lastSaved().nextAttemptAt)
    }

    @Test
    fun `quarantines with the reason attached`() {
        existing()
        recorder.markQuarantined(work, "too old")

        assertEquals(OutboxStatus.QUARANTINED, lastSaved().status)
        assertEquals("too old", lastSaved().lastError)
    }

    @Test
    fun `records a failure for retry`() {
        existing()
        recorder.markFailed(work, RuntimeException("boom"))

        assertEquals(OutboxStatus.PENDING, lastSaved().status)
        assertEquals(1, lastSaved().attemptCount)
        assertEquals("boom", lastSaved().lastError)
    }

    @Test
    fun `dead-letters once attempts are exhausted`() {
        existing()
        repeat(properties.maxAttempts) { recorder.markFailed(work, RuntimeException("boom")) }

        assertEquals(OutboxStatus.FAILED, lastSaved().status)
    }

    @Test
    fun `falls back to the exception type when there is no message`() {
        existing()
        recorder.markFailed(work, NullPointerException())

        assertEquals("NullPointerException", lastSaved().lastError)
    }

    @Test
    fun `does nothing when the message has vanished`() {
        // Not a crash: a missing row means something else already handled it, and
        // throwing here would fail a batch over bookkeeping.
        every { messages.findByRawEventId(any(), any()) } returns null

        recorder.markDone(work)

        verify(exactly = 0) { messages.save(any()) }
    }
}
