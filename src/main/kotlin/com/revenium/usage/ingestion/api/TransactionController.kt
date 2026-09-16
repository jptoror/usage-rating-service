package com.revenium.usage.ingestion.api

import tools.jackson.databind.ObjectMapper
import com.revenium.usage.ingestion.application.IngestionService
import com.revenium.usage.ingestion.domain.IngestionResult
import com.revenium.usage.ingestion.domain.RawTransactionInput
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "Usage transaction ingestion")
class TransactionController(
    private val ingestionService: IngestionService,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping
    @Operation(
        summary = "Ingest a usage transaction",
        description = """
            Records the transaction durably and queues it for rating. The response does
            not wait for rating to complete.

            A duplicate returns 200 rather than 409: re-delivery is the upstream retry
            working as intended, not a client error, and a 4xx would prompt integrations
            to retry or alert for something that behaved correctly. The `status` field
            distinguishes the cases, and duplicates are visible in reconciliation.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Accepted and queued for rating"),
        ApiResponse(responseCode = "200", description = "Already recorded; no new charge created"),
        ApiResponse(responseCode = "422", description = "Failed validation; recorded as evidence"),
        ApiResponse(responseCode = "400", description = "No tenant in scope"),
        ApiResponse(responseCode = "403", description = "Body names a different tenant than the header"),
    )
    fun ingest(
        @Parameter(description = "Authoritative tenant identity", required = true)
        @RequestHeader("X-Tenant-Id") tenantHeader: String,
        @RequestBody request: TransactionRequest,
    ): ResponseEntity<TransactionResponse> {
        val result = ingestionService.ingest(request.toInput())
        return result.toResponseEntity()
    }

    @PostMapping("/batch")
    @Operation(
        summary = "Ingest a batch of transactions",
        description = """
            Each item is ingested independently in its own transaction, so one invalid
            item does not reject the rest of the batch. The response reports the outcome
            per item. Always 200: the batch was processed, even when individual items
            were rejected.
        """,
    )
    fun ingestBatch(
        @RequestHeader("X-Tenant-Id") tenantHeader: String,
        @RequestBody requests: List<TransactionRequest>,
    ): ResponseEntity<BatchTransactionResponse> {
        val results = requests.map { ingestionService.ingest(it.toInput()) }

        return ResponseEntity.ok(
            BatchTransactionResponse(
                accepted = results.count { it is IngestionResult.Accepted },
                duplicates = results.count { it is IngestionResult.Duplicate },
                rejected = results.count { it is IngestionResult.Rejected },
                results = results.map { it.toResponse() },
            )
        )
    }

    private fun TransactionRequest.toInput(): RawTransactionInput {
        // The payload is re-serialised from the parsed request rather than captured as
        // the raw request body, so the hash is stable across formatting differences:
        // the same event pretty-printed and minified must hash identically, or a retry
        // through a different gateway would look like a payload conflict.
        val canonicalPayload = objectMapper.writeValueAsString(this)
        return RawTransactionInput(
            eventId = eventId,
            tenantId = tenantId,
            customerId = customerId,
            transactionCode = transactionCode,
            occurredAt = occurredAt,
            quantity = effectiveQuantity,
            rawPayload = canonicalPayload,
            payloadHash = sha256(canonicalPayload),
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

internal fun IngestionResult.toResponse(): TransactionResponse = when (this) {
    is IngestionResult.Accepted -> TransactionResponse(
        status = "ACCEPTED",
        eventId = eventId.toString(),
        receivedAt = receivedAt,
    )

    is IngestionResult.Duplicate -> TransactionResponse(
        status = "DUPLICATE",
        eventId = eventId.toString(),
        originalReceivedAt = originalReceivedAt,
        payloadConflict = if (conflictingPayload) true else null,
    )

    is IngestionResult.Rejected -> TransactionResponse(
        status = "REJECTED",
        eventId = eventId?.toString(),
        failures = failures.map { FailureDetail(it.field, it.reason) },
    )
}

internal fun IngestionResult.toResponseEntity(): ResponseEntity<TransactionResponse> = when (this) {
    is IngestionResult.Accepted -> ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse())
    is IngestionResult.Duplicate -> ResponseEntity.ok(toResponse())
    is IngestionResult.Rejected -> ResponseEntity.unprocessableEntity().body(toResponse())
}
