package com.revenium.usage.processing.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class OutboxSchedulerTest {

    private val worker = mockk<OutboxWorker>()
    private val scheduler = OutboxScheduler(worker)

    @Test
    fun `drives one polling cycle per tick`() {
        every { worker.pollOnce() } returns 3

        scheduler.poll()

        verify(exactly = 1) { worker.pollOnce() }
    }

    @Test
    fun `swallows a failure so the schedule survives`() {
        // An exception escaping a @Scheduled method cancels the schedule, and the queue
        // would then stop draining silently -- the worst possible failure mode for a
        // billing pipeline.
        every { worker.pollOnce() } throws RuntimeException("database unreachable")

        scheduler.poll()

        every { worker.pollOnce() } returns 1
        scheduler.poll()
        verify(exactly = 2) { worker.pollOnce() }
    }

    @Test
    fun `stops claiming new work once shutdown has begun`() {
        // On SIGTERM the in-flight batch finishes, but no new cycle starts. Anything
        // unclaimed stays PENDING for another instance.
        every { worker.pollOnce() } returns 1

        scheduler.shutdown()
        scheduler.poll()

        verify(exactly = 0) { worker.pollOnce() }
    }
}
