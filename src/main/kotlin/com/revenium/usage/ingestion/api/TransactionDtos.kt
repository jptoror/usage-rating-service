package com.revenium.usage.ingestion.api

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant

/**
 * The incoming transaction contract.
 *
 * Fields are nullable and validation happens in the domain rather than through bean
 * validation annotations. That is deliberate: the brief requires a *clear rejection
 * result* listing what was wrong, and collecting every failure in one pass produces a
 * better response than bean validation's per-annotation reporting. It also keeps the
 * rule in the domain, where it is unit-testable without a web context.
 *
 * ### Contract extensions
 *
 * Both are additive and backward compatible with the contract in the brief:
 *
 * - `quantity` and `currency` are accepted at the top level, with `metadata.quantity`
 *   as a fallback. A producer using the original shape keeps working unchanged.
 * - Unknown fields are ignored rather than rejected, so upstream can add fields without
 *   a coordinated deployment.
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

@Schema(description = "The outcome of an ingestion attempt")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TransactionResponse(

    @field:Schema(
        description = """
            ACCEPTED  - recorded for the first time, queued for rating (HTTP 202)
            DUPLICATE - already recorded; the retry worked as intended (HTTP 200)
            REJECTED  - failed validation (HTTP 422)
        """,
        example = "ACCEPTED",
    )
    val status: String,

    val eventId: String?,

    @field:Schema(description = "When this service first recorded the event")
    val receivedAt: Instant? = null,

    @field:Schema(description = "When the original delivery was recorded. Present for duplicates.")
    val originalReceivedAt: Instant? = null,

    @field:Schema(
        description = "True when a duplicate arrived with a different body. Recorded for reconciliation.",
    )
    val payloadConflict: Boolean? = null,

    @field:Schema(description = "Every validation failure, not just the first")
    val failures: List<FailureDetail>? = null,
)

@Schema(description = "One reason a transaction was rejected")
data class FailureDetail(val field: String, val reason: String)

@Schema(description = "Per-item results for a batch submission")
data class BatchTransactionResponse(
    val accepted: Int,
    val duplicates: Int,
    val rejected: Int,
    val results: List<TransactionResponse>,
)
