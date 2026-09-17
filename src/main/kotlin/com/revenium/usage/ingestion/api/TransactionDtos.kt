package com.revenium.usage.ingestion.api

import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant

/**
 * The incoming transaction contract.
 *
 * Fields are nullable because validation belongs in the domain, where one pass collects
 * every failure. Bean validation would report them one annotation at a time and would
 * need a web context to test.
 *
 * Two additive extensions to the contract in the brief: `quantity` and `currency` are
 * accepted at the top level with `metadata` as a fallback, and unknown fields are
 * ignored. A producer using the original shape keeps working.
 */
@Schema(description = "A billable usage transaction from the upstream integration")
data class TransactionRequest(

    @field:Schema(
        description = "Upstream identifier. Re-delivering the same value is treated as a duplicate.",
        example = "73d4e120-77d0-4f11-a6d2-f3b43b430d9c",
    )
    val eventId: String? = null,

    @field:Schema(
        description = "Must match the X-Tenant-Id header when present. The header is authoritative.",
        example = "tenant-a",
    )
    val tenantId: String? = null,

    @field:Schema(example = "customer-42")
    val customerId: String? = null,

    @field:Schema(example = "VEHICLE_REGISTRATION")
    val transactionCode: String? = null,

    @field:Schema(
        description = "When the usage happened. Selects both the billing period and the pricing rule.",
        example = "2026-08-15T14:22:31Z",
    )
    val occurredAt: String? = null,

    @field:Schema(description = "Defaults to 1 when absent. Falls back to metadata.quantity.", example = "2")
    val quantity: BigDecimal? = null,

    @field:Schema(description = "Free-form upstream metadata, preserved verbatim.")
    val metadata: Map<String, Any?> = emptyMap(),
) {
    /** Top-level `quantity` wins; `metadata.quantity` is the backward-compatible fallback. */
    @get:JsonIgnore
    val effectiveQuantity: BigDecimal?
        get() = quantity ?: metadata["quantity"]?.let { raw ->
            when (raw) {
                is BigDecimal -> raw
                is Number -> BigDecimal(raw.toString())
                is String -> runCatching { BigDecimal(raw.trim()) }.getOrNull()
                else -> null
            }
        }
}

/**
 * The outcome of an ingestion attempt.
 *
 * One variant per outcome rather than one class with a `status` and five nullable
 * fields: `originalReceivedAt` is meaningless on an acceptance and `failures` on a
 * duplicate, and a single class cannot say so. Each variant carries only what its case
 * has, so an impossible combination cannot be constructed.
 *
 * The wire format is unchanged: `status` is still a field, and absent fields are absent
 * because the variant does not declare them rather than because they are null.
 */
@Schema(
    description = "The outcome of an ingestion attempt. The status field selects the shape.",
    discriminatorProperty = "status",
    oneOf = [
        AcceptedResponse::class,
        DuplicateResponse::class,
        RejectedResponse::class,
    ],
)
sealed interface TransactionResponse {
    val status: String
    val eventId: String?
}

/** Recorded for the first time and queued for rating. HTTP 202. */
@Schema(description = "Recorded for the first time and queued for rating")
data class AcceptedResponse(
    override val eventId: String,

    @field:Schema(description = "When this service first recorded the event")
    val receivedAt: Instant,
) : TransactionResponse {
    override val status: String get() = "ACCEPTED"
}

/** Already recorded by an earlier delivery. HTTP 200: the retry worked as intended. */
@Schema(description = "Already recorded; the upstream retry worked as intended")
data class DuplicateResponse(
    override val eventId: String,

    @field:Schema(description = "When the original delivery was recorded")
    val originalReceivedAt: Instant,

    @field:Schema(
        description = "Present only when the duplicate arrived with a different body. " +
            "Recorded for reconciliation; the original payload is kept.",
    )
    val payloadConflict: Boolean? = null,
) : TransactionResponse {
    override val status: String get() = "DUPLICATE"
}

/** Failed validation. HTTP 422. The attempt is still recorded as evidence. */
@Schema(description = "Failed validation; recorded as evidence")
data class RejectedResponse(
    /** Null when the rejection is that `eventId` itself was missing or unparseable. */
    override val eventId: String?,

    @field:Schema(description = "Every validation failure, not just the first")
    val failures: List<FailureDetail>,
) : TransactionResponse {
    override val status: String get() = "REJECTED"
}

@Schema(description = "One reason a transaction was rejected")
data class FailureDetail(val field: String, val reason: String)

@Schema(description = "Per-item results for a batch submission")
data class BatchTransactionResponse(
    val accepted: Int,
    val duplicates: Int,
    val rejected: Int,
    val results: List<TransactionResponse>,
)
