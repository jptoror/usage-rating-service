package com.revenium.usage.processing.infrastructure.persistence

import com.revenium.usage.processing.domain.model.OutboxMessage
import com.revenium.usage.processing.domain.model.OutboxStatus
import com.revenium.usage.tenancy.TenantId
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class OutboxMessageEntityTest {

    private val now = Instant.parse("2026-09-16T12:00:00Z")

    private fun message() = OutboxMessage(
        tenantId = TenantId("tenant-a"),
        rawEventId = 11L,
        createdAt = now,
        nextAttemptAt = now,
        updatedAt = now,
        id = 4L,
    )

    @Test
    fun `a pending message round trips`() {
        val original = message()

        assertEquals(original, OutboxMessageEntity.fromDomain(original).toDomain())
    }

    @Test
    fun `a failed message round trips with its attempt count and backoff`() {
        // Losing the attempt count would reset the retry budget on every restart, so a
        // poison message would be retried for ever instead of dead-lettering.
        val failed = message().markFailed(now, "boom", maxAttempts = 5, backoffBase = Duration.ofSeconds(10))

        val mapped = OutboxMessageEntity.fromDomain(failed).toDomain()

        assertEquals(failed, mapped)
        assertEquals(1, mapped.attemptCount)
        assertEquals(now.plusSeconds(10), mapped.nextAttemptAt)
        assertEquals("boom", mapped.lastError)
    }

    @Test
    fun `the status maps by name, not by ordinal`() {
        // An ordinal would silently reinterpret every existing row the moment a constant
        // was inserted in the middle of the enum.
        val quarantined = message().markQuarantined(now, "400 days old")

        assertEquals(OutboxStatus.QUARANTINED, OutboxMessageEntity.fromDomain(quarantined).status)
        assertEquals(OutboxStatus.QUARANTINED, OutboxMessageEntity.fromDomain(quarantined).toDomain().status)
    }

    @Test
    fun `the claiming instance round trips as evidence`() {
        val claimed = message().copy(processedBy = "worker-2")

        assertEquals("worker-2", OutboxMessageEntity.fromDomain(claimed).toDomain().processedBy)
    }
}
