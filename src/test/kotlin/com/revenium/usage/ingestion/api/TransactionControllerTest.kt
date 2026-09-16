package com.revenium.usage.ingestion.api

import com.ninjasquad.springmockk.MockkBean
import com.revenium.usage.ingestion.application.IngestionService
import com.revenium.usage.ingestion.domain.IngestionResult
import com.revenium.usage.ingestion.domain.RawTransactionInput
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.ingestion.domain.ValidationFailure
import com.revenium.usage.tenancy.MissingTenantException
import io.mockk.every
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A web slice: the HTTP contract without a database or an application context.
 *
 * The status codes here are a deliberate design decision the brief asks about, so they
 * are asserted rather than assumed.
 */
@WebMvcTest(TransactionController::class)
class TransactionControllerTest(@Autowired val mvc: MockMvc) {

    @MockkBean
    private lateinit var ingestionService: IngestionService

    private val eventId = UUID.fromString("73d4e120-77d0-4f11-a6d2-f3b43b430d9c")
    private val now = Instant.parse("2026-09-16T12:00:00Z")

    private val validBody = """
        {
          "eventId": "$eventId",
          "tenantId": "tenant-a",
          "customerId": "customer-42",
          "transactionCode": "VEHICLE_REGISTRATION",
          "occurredAt": "2026-08-15T14:22:31Z",
          "metadata": {"source": "upstream-api", "quantity": 2, "batchId": "batch-01"}
        }
    """.trimIndent()

    private fun postTransaction(body: String = validBody, tenant: String? = "tenant-a") =
        mvc.perform(
            post("/api/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .apply { tenant?.let { header("X-Tenant-Id", it) } }
        )

    // --- status codes ------------------------------------------------------

    @Test
    fun `a new transaction is 202 Accepted`() {
        // 202, not 200: the work has been accepted but rating has not happened yet.
        every { ingestionService.ingest(any()) } returns
            IngestionResult.Accepted(EventId(eventId), 1L, now)

        postTransaction()
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.eventId").value(eventId.toString()))
    }

    @Test
    fun `a duplicate is 200 OK, not 409`() {
        // The decision worth defending: re-delivery is the upstream retry working as
        // intended, not a client error. A 4xx would make integrations retry or alert
        // over something that behaved correctly. The status field distinguishes it.
        every { ingestionService.ingest(any()) } returns
            IngestionResult.Duplicate(EventId(eventId), 1L, now, conflictingPayload = false)

        postTransaction()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("DUPLICATE"))
            .andExpect(jsonPath("$.originalReceivedAt").exists())
    }

    @Test
    fun `a duplicate with a different payload is flagged in the response`() {
        every { ingestionService.ingest(any()) } returns
            IngestionResult.Duplicate(EventId(eventId), 1L, now, conflictingPayload = true)

        postTransaction()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.payloadConflict").value(true))
    }

    @Test
    fun `an identical duplicate does not mention a conflict at all`() {
        every { ingestionService.ingest(any()) } returns
            IngestionResult.Duplicate(EventId(eventId), 1L, now, conflictingPayload = false)

        postTransaction().andExpect(jsonPath("$.payloadConflict").doesNotExist())
    }

    @Test
    fun `a rejected transaction is 422 with every failure listed`() {
        every { ingestionService.ingest(any()) } returns IngestionResult.Rejected(
            EventId(eventId),
            listOf(
                ValidationFailure("customerId", "is required"),
                ValidationFailure("quantity", "must be positive"),
            ),
        )

        postTransaction()
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.failures.length()").value(2))
            .andExpect(jsonPath("$.failures[0].field").value("customerId"))
    }

    @Test
    fun `a missing tenant header is 400`() {
        postTransaction(tenant = null)
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
    }

    @Test
    fun `a tenancy failure from the service is 400 as problem json`() {
        every { ingestionService.ingest(any()) } throws MissingTenantException("no tenant")

        postTransaction()
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.title").value("Missing tenant"))
    }

    @Test
    fun `a malformed body is 400, not 500`() {
        mvc.perform(
            post("/api/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", "tenant-a")
                .content("{ not json")
        ).andExpect(status().isBadRequest)
    }

    // --- contract handling -------------------------------------------------

    @Test
    fun `reads quantity from metadata for backward compatibility`() {
        // The contract in the brief carries quantity inside metadata. Supporting the
        // top-level field is additive; this must keep working unchanged.
        val captured = slot<RawTransactionInput>()
        every { ingestionService.ingest(capture(captured)) } returns
            IngestionResult.Accepted(EventId(eventId), 1L, now)

        postTransaction().andExpect(status().isAccepted)

        assertEquals(0, captured.captured.quantity?.compareTo(java.math.BigDecimal("2")))
    }

    @Test
    fun `a top-level quantity takes precedence over metadata`() {
        val captured = slot<RawTransactionInput>()
        every { ingestionService.ingest(capture(captured)) } returns
            IngestionResult.Accepted(EventId(eventId), 1L, now)

        postTransaction(
            """
            {
              "eventId": "$eventId",
              "customerId": "customer-42",
              "transactionCode": "CODE",
              "occurredAt": "2026-08-15T14:22:31Z",
              "quantity": 5,
              "metadata": {"quantity": 2}
            }
            """.trimIndent()
        ).andExpect(status().isAccepted)

        assertEquals(0, captured.captured.quantity?.compareTo(java.math.BigDecimal("5")))
    }

    @Test
    fun `ignores unknown fields so upstream can add them safely`() {
        every { ingestionService.ingest(any()) } returns
            IngestionResult.Accepted(EventId(eventId), 1L, now)

        postTransaction(
            """
            {
              "eventId": "$eventId",
              "customerId": "customer-42",
              "transactionCode": "CODE",
              "occurredAt": "2026-08-15T14:22:31Z",
              "somethingNew": "added by a later producer version"
            }
            """.trimIndent()
        ).andExpect(status().isAccepted)
    }

    @Test
    fun `hashes the payload so formatting differences are not conflicts`() {
        // The same event minified and pretty-printed must hash identically, or a retry
        // routed through a different gateway would look like a payload conflict.
        val hashes = mutableListOf<String>()
        every { ingestionService.ingest(any()) } answers {
            hashes += firstArg<RawTransactionInput>().payloadHash
            IngestionResult.Accepted(EventId(eventId), 1L, now)
        }

        postTransaction(validBody)
        postTransaction(validBody.replace("\n", "").replace("  ", ""))

        assertEquals(2, hashes.size)
        assertEquals(hashes[0], hashes[1])
    }

    @Test
    fun `different payloads hash differently`() {
        val hashes = mutableListOf<String>()
        every { ingestionService.ingest(any()) } answers {
            hashes += firstArg<RawTransactionInput>().payloadHash
            IngestionResult.Accepted(EventId(eventId), 1L, now)
        }

        postTransaction(validBody)
        postTransaction(validBody.replace("customer-42", "customer-99"))

        assertTrue(hashes[0] != hashes[1])
    }

    // --- batch -------------------------------------------------------------

    @Test
    fun `a batch reports the outcome of each item`() {
        every { ingestionService.ingest(any()) } returnsMany listOf(
            IngestionResult.Accepted(EventId(eventId), 1L, now),
            IngestionResult.Duplicate(EventId(eventId), 1L, now, conflictingPayload = false),
            IngestionResult.Rejected(null, listOf(ValidationFailure("eventId", "is required"))),
        )

        mvc.perform(
            post("/api/v1/transactions/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", "tenant-a")
                .content("[$validBody,$validBody,$validBody]")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(1))
            .andExpect(jsonPath("$.duplicates").value(1))
            .andExpect(jsonPath("$.rejected").value(1))
            .andExpect(jsonPath("$.results.length()").value(3))
    }

    @Test
    fun `one invalid item does not fail the whole batch`() {
        // Rejecting the batch would punish the 999 good records for one bad one.
        every { ingestionService.ingest(any()) } returnsMany listOf(
            IngestionResult.Rejected(null, listOf(ValidationFailure("eventId", "is required"))),
            IngestionResult.Accepted(EventId(eventId), 1L, now),
        )

        mvc.perform(
            post("/api/v1/transactions/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", "tenant-a")
                .content("[$validBody,$validBody]")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(1))
            .andExpect(jsonPath("$.rejected").value(1))
    }
}
