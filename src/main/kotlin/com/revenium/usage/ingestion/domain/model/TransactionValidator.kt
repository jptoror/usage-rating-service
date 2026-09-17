package com.revenium.usage.ingestion.domain.model

import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.shared.domain.Quantity
import com.revenium.usage.shared.domain.TransactionCode
import com.revenium.usage.tenancy.TenantId
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The raw shape of an incoming transaction: every field nullable because this is what actually
 * arrived, not what should have. Validation yields a [UsageTransaction] or a list of failures,
 * with no partially valid state in between.
 */
data class RawTransactionInput(
    val eventId: String?,
    val tenantId: String?,
    val customerId: String?,
    val transactionCode: String?,
    val occurredAt: String?,
    val quantity: BigDecimal?,
    val rawPayload: String,
    val payloadHash: String,
)

/**
 * Validates incoming transactions against the tenant in scope.
 *
 * Pure domain logic; the [Clock] is injected so "too far in the future" does not depend on
 * when the suite runs. Collects all failures rather than stopping at the first, so a caller
 * fixes everything in one pass instead of one field per deployment.
 *
 * Constructed by `IngestionConfiguration` rather than component-scanned: a constructor default
 * on a scanned bean makes Kotlin emit a synthetic `DefaultConstructorMarker` parameter that
 * Spring tries to autowire — the failure that once stopped this application from starting.
 */
class TransactionValidator(
    private val clock: Clock,
    private val maxFutureSkew: Duration,
) {

    fun validate(input: RawTransactionInput, contextTenant: TenantId): ValidationOutcome {
        val failures = mutableListOf<ValidationFailure>()

        val eventId = EventId.parseOrNull(input.eventId)
        if (eventId == null) {
            failures += ValidationFailure(
                "eventId",
                if (input.eventId.isNullOrBlank()) "is required" else "must be a valid UUID",
            )
        }

        // The header is identity; a body tenantId is only data checked against it. Trusting
        // the body would let any caller bill any tenant by editing a field.
        if (!input.tenantId.isNullOrBlank() && input.tenantId.trim() != contextTenant.value) {
            failures += ValidationFailure(
                "tenantId",
                "does not match the authenticated tenant",
            )
        }

        val customerId = runCatching { input.customerId?.trim()?.let(::CustomerId) }.getOrNull()
        if (customerId == null) {
            failures += ValidationFailure(
                "customerId",
                if (input.customerId.isNullOrBlank()) "is required"
                else "must be at most ${CustomerId.MAX_LENGTH} characters",
            )
        }

        val transactionCode = runCatching { input.transactionCode?.trim()?.let(::TransactionCode) }.getOrNull()
        if (transactionCode == null) {
            failures += ValidationFailure(
                "transactionCode",
                if (input.transactionCode.isNullOrBlank()) "is required"
                else "must be at most ${TransactionCode.MAX_LENGTH} characters",
            )
        }

        val occurredAt = parseInstant(input.occurredAt)
        if (occurredAt == null) {
            failures += ValidationFailure(
                "occurredAt",
                if (input.occurredAt.isNullOrBlank()) "is required" else "must be an ISO-8601 instant",
            )
        } else if (occurredAt.isAfter(clock.instant().plus(maxFutureSkew))) {
            // A future timestamp would select a rule not yet in effect and land in a period
            // that has not started. Small clock skew is tolerated.
            failures += ValidationFailure(
                "occurredAt",
                "is too far in the future (max skew ${maxFutureSkew.toMinutes()} minutes)",
            )
        }

        // Absent quantity defaults to 1: in the original contract it lives in metadata and
        // is not always present.
        val quantity = when {
            input.quantity == null -> Quantity.ONE
            else -> runCatching { Quantity.of(input.quantity) }.getOrNull()
        }
        if (quantity == null) {
            failures += ValidationFailure(
                "quantity",
                "must be a positive number with at most ${Quantity.SCALE} decimal places",
            )
        }

        // Smart-cast rather than `!!`: the compiler proves the non-null case below.
        if (eventId == null || customerId == null || transactionCode == null ||
            occurredAt == null || quantity == null
        ) {
            check(failures.isNotEmpty()) { "Validation produced no value and no failure" }
            return ValidationOutcome.Invalid(eventId, failures)
        }

        if (failures.isNotEmpty()) {
            return ValidationOutcome.Invalid(eventId, failures)
        }

        return ValidationOutcome.Valid(
            UsageTransaction(
                eventId = eventId,
                tenantId = contextTenant,
                customerId = customerId,
                transactionCode = transactionCode,
                occurredAt = occurredAt,
                quantity = quantity,
                rawPayload = input.rawPayload,
                payloadHash = input.payloadHash,
            )
        )
    }

    private fun parseInstant(raw: String?): Instant? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

/** Validation either yields a usable transaction or the reasons it does not. */
sealed interface ValidationOutcome {
    data class Valid(val transaction: UsageTransaction) : ValidationOutcome
    data class Invalid(val eventId: EventId?, val failures: List<ValidationFailure>) : ValidationOutcome
}
