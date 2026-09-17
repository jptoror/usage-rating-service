package com.revenium.usage.ingestion.domain.model

import com.revenium.usage.tenancy.TenantId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionValidatorTest {

    private val now = Instant.parse("2026-09-16T12:00:00Z")
    // The skew is supplied explicitly now that the class carries no default: the
    // configuration lives in IngestionProperties, not in the domain class.
    private val defaultSkew: Duration = Duration.ofMinutes(5)
    private val validator = TransactionValidator(Clock.fixed(now, ZoneOffset.UTC), defaultSkew)
    private val tenant = TenantId("tenant-a")

    private fun input(
        eventId: String? = "73d4e120-77d0-4f11-a6d2-f3b43b430d9c",
        tenantId: String? = "tenant-a",
        customerId: String? = "customer-42",
        transactionCode: String? = "VEHICLE_REGISTRATION",
        occurredAt: String? = "2026-08-15T14:22:31Z",
        quantity: BigDecimal? = BigDecimal("2"),
    ) = RawTransactionInput(
        eventId = eventId,
        tenantId = tenantId,
        customerId = customerId,
        transactionCode = transactionCode,
        occurredAt = occurredAt,
        quantity = quantity,
        rawPayload = "{}",
        payloadHash = "hash",
    )

    private fun failuresOf(outcome: ValidationOutcome): List<ValidationFailure> =
        assertIs<ValidationOutcome.Invalid>(outcome).failures

    // --- the happy path ----------------------------------------------------

    @Test
    fun `accepts a well formed transaction`() {
        val outcome = validator.validate(input(), tenant)

        val transaction = assertIs<ValidationOutcome.Valid>(outcome).transaction
        assertEquals("customer-42", transaction.customerId.value)
        assertEquals("VEHICLE_REGISTRATION", transaction.transactionCode.value)
        assertEquals(Instant.parse("2026-08-15T14:22:31Z"), transaction.occurredAt)
        assertEquals(0, transaction.quantity.value.compareTo(BigDecimal("2")))
    }

    @Test
    fun `takes the tenant from the context, not the body`() {
        // The body may omit tenantId entirely; identity comes from the header.
        val outcome = validator.validate(input(tenantId = null), tenant)
        assertEquals(tenant, assertIs<ValidationOutcome.Valid>(outcome).transaction.tenantId)
    }

    @Test
    fun `trims surrounding whitespace on identifiers`() {
        val outcome = validator.validate(
            input(customerId = "  customer-42  ", transactionCode = "  CODE  "),
            tenant,
        )
        val transaction = assertIs<ValidationOutcome.Valid>(outcome).transaction
        assertEquals("customer-42", transaction.customerId.value)
        assertEquals("CODE", transaction.transactionCode.value)
    }

    // --- tenant mismatch ---------------------------------------------------

    @Test
    fun `rejects a body naming a different tenant`() {
        // The attack: bill another tenant by editing a JSON field. The header is
        // identity and the body is data to check against it.
        val failures = failuresOf(validator.validate(input(tenantId = "tenant-b"), tenant))
        assertTrue(failures.any { it.field == "tenantId" })
    }

    // --- required fields ---------------------------------------------------

    @Test
    fun `rejects a missing event id`() {
        val failures = failuresOf(validator.validate(input(eventId = null), tenant))
        assertEquals(listOf("eventId" to "is required"), failures.map { it.field to it.reason })
    }

    @Test
    fun `rejects a malformed event id`() {
        val failures = failuresOf(validator.validate(input(eventId = "not-a-uuid"), tenant))
        assertTrue(failures.single().reason.contains("UUID"))
    }

    @Test
    fun `rejects a missing customer`() {
        assertTrue(failuresOf(validator.validate(input(customerId = null), tenant)).any { it.field == "customerId" })
    }

    @Test
    fun `rejects a blank customer`() {
        assertTrue(failuresOf(validator.validate(input(customerId = "   "), tenant)).any { it.field == "customerId" })
    }

    @Test
    fun `rejects a missing transaction code`() {
        assertTrue(
            failuresOf(validator.validate(input(transactionCode = null), tenant))
                .any { it.field == "transactionCode" }
        )
    }

    @Test
    fun `rejects an over-long identifier rather than truncating it`() {
        // Truncating would silently bill a different customer.
        val failures = failuresOf(validator.validate(input(customerId = "c".repeat(201)), tenant))
        assertTrue(failures.any { it.field == "customerId" })
    }

    // --- occurredAt --------------------------------------------------------

    @Test
    fun `rejects a missing occurredAt`() {
        assertTrue(failuresOf(validator.validate(input(occurredAt = null), tenant)).any { it.field == "occurredAt" })
    }

    @Test
    fun `rejects an unparseable occurredAt`() {
        val failures = failuresOf(validator.validate(input(occurredAt = "15-08-2026"), tenant))
        assertTrue(failures.single().reason.contains("ISO-8601"))
    }

    @Test
    fun `accepts a past occurredAt, however old`() {
        // Late arrival is a billing policy decision, not a validation error: the
        // event is real usage and must not be discarded at the door.
        val outcome = validator.validate(input(occurredAt = "2019-01-01T00:00:00Z"), tenant)
        assertIs<ValidationOutcome.Valid>(outcome)
    }

    @Test
    fun `tolerates small clock skew into the future`() {
        // Boundary: exactly at the skew limit is still accepted.
        val atLimit = now.plus(defaultSkew)
        assertIs<ValidationOutcome.Valid>(validator.validate(input(occurredAt = atLimit.toString()), tenant))
    }

    @Test
    fun `rejects an occurredAt beyond the skew tolerance`() {
        // A future timestamp would select a rule that is not in effect and land in a
        // period that has not started.
        val beyondLimit = now.plus(defaultSkew).plusSeconds(1)
        val failures = failuresOf(validator.validate(input(occurredAt = beyondLimit.toString()), tenant))
        assertTrue(failures.single().reason.contains("future"))
    }

    @Test
    fun `respects a configured skew tolerance`() {
        val strict = TransactionValidator(Clock.fixed(now, ZoneOffset.UTC), Duration.ZERO)
        val justAhead = now.plusSeconds(1)
        assertTrue(
            failuresOf(strict.validate(input(occurredAt = justAhead.toString()), tenant))
                .any { it.field == "occurredAt" }
        )
    }

    // --- quantity ----------------------------------------------------------

    @Test
    fun `defaults an absent quantity to one`() {
        // Matches the original contract, where quantity lives in metadata and is not
        // always present.
        val outcome = validator.validate(input(quantity = null), tenant)
        val transaction = assertIs<ValidationOutcome.Valid>(outcome).transaction
        assertEquals(0, transaction.quantity.value.compareTo(BigDecimal.ONE))
    }

    @Test
    fun `accepts a fractional quantity`() {
        val outcome = validator.validate(input(quantity = BigDecimal("2.5")), tenant)
        assertEquals(0, assertIs<ValidationOutcome.Valid>(outcome).transaction.quantity.value.compareTo(BigDecimal("2.5")))
    }

    @Test
    fun `rejects a zero quantity`() {
        assertTrue(failuresOf(validator.validate(input(quantity = BigDecimal.ZERO), tenant)).any { it.field == "quantity" })
    }

    @Test
    fun `rejects a negative quantity`() {
        // A negative quantity would produce a negative charge -- a credit note by
        // accident.
        assertTrue(
            failuresOf(validator.validate(input(quantity = BigDecimal("-1")), tenant))
                .any { it.field == "quantity" }
        )
    }

    @Test
    fun `rejects a quantity with more precision than can be stored`() {
        val failures = failuresOf(validator.validate(input(quantity = BigDecimal("1.1234567")), tenant))
        assertTrue(failures.any { it.field == "quantity" })
    }

    // --- reporting ---------------------------------------------------------

    @Test
    fun `reports every failure at once, not just the first`() {
        // One response listing everything wrong beats an integration fixing one field
        // per deployment.
        val failures = failuresOf(
            validator.validate(
                input(eventId = null, customerId = null, transactionCode = null, quantity = BigDecimal.ZERO),
                tenant,
            )
        )
        assertEquals(
            setOf("eventId", "customerId", "transactionCode", "quantity"),
            failures.map { it.field }.toSet(),
        )
    }

    @Test
    fun `reports the event id alongside failures when it could be parsed`() {
        // Lets the caller correlate the rejection with what they sent.
        val outcome = validator.validate(input(customerId = null), tenant)
        assertEquals(
            "73d4e120-77d0-4f11-a6d2-f3b43b430d9c",
            assertIs<ValidationOutcome.Invalid>(outcome).eventId.toString(),
        )
    }

    @Test
    fun `reports a null event id when it could not be parsed`() {
        val outcome = validator.validate(input(eventId = "garbage"), tenant)
        assertNull(assertIs<ValidationOutcome.Invalid>(outcome).eventId)
    }
}
