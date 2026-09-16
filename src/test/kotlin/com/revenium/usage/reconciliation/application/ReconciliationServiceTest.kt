package com.revenium.usage.reconciliation.application

import com.revenium.usage.reconciliation.domain.EventState
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.tenancy.MissingTenantException
import com.revenium.usage.tenancy.TenantContext
import com.revenium.usage.tenancy.TenantId
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers how the service assembles a report from its queries.
 *
 * The SQL itself is verified against a real PostgreSQL in the integration suite; mocking
 * a database here would only assert that the strings match themselves. What this covers
 * is the assembly: which counts land in which state, and whether the totals balance.
 */
class ReconciliationServiceTest {

    private val now = Instant.parse("2026-09-16T12:00:00Z")
    private val usd: Currency = Currency.getInstance("USD")
    private val tenant = TenantId("tenant-a")
    private val customer = CustomerId("customer-42")
    private val august = BillingPeriod.parse("2026-08")

    private val jdbc = mockk<JdbcTemplate>()
    private val service = ReconciliationService(jdbc, usd, Clock.fixed(now, ZoneOffset.UTC))

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    /**
     * Stubs the four queries the report makes, matched on a distinctive fragment of each
     * so the stubbing does not depend on whitespace.
     */
    private fun stubReport(
        received: Long = 0,
        rejected: Long = 0,
        duplicates: Long = 0,
        outboxByStatus: Map<String, Long> = emptyMap(),
        ratedOpen: Pair<Long, String>? = null,
        ratedClosed: Pair<Long, String>? = null,
    ) {
        every {
            jdbc.queryForObject(match<String> { it.contains("FROM raw_event") }, Long::class.java, *anyVararg())
        } returns received
        every {
            jdbc.queryForObject(match<String> { it.contains("FROM rejected_event") }, Long::class.java, *anyVararg())
        } returns rejected
        every {
            jdbc.queryForObject(match<String> { it.contains("FROM event_conflict") }, Long::class.java, *anyVararg())
        } returns duplicates

        every {
            jdbc.query(
                match<String> { it.contains("GROUP BY o.status") },
                any<org.springframework.jdbc.core.RowMapper<Pair<String, Long>>>(),
                *anyVararg(),
            )
        } returns outboxByStatus.toList()

        val ratedRows = buildList {
            ratedOpen?.let { add(Triple("OPEN", it.first, java.math.BigDecimal(it.second) to "USD")) }
            ratedClosed?.let { add(Triple("CLOSED", it.first, java.math.BigDecimal(it.second) to "USD")) }
        }
        every {
            jdbc.query(
                match<String> { it.contains("FROM rated_transaction") },
                any<RowMapper<Triple<String, Long, Pair<java.math.BigDecimal, String>>>>(),
                *anyVararg(),
            )
        } returns ratedRows
    }

    @Test
    fun `counts each state from its own query`() {
        stubReport(
            received = 10,
            rejected = 2,
            duplicates = 1,
            outboxByStatus = mapOf("PENDING" to 1L, "UNRATED" to 2L, "FAILED" to 1L, "QUARANTINED" to 1L),
            ratedOpen = 5L to "50.0000",
        )

        val report = TenantContext.runAs(tenant) { service.report(customer, august) }

        assertEquals(2, report.countOf(EventState.REJECTED))
        assertEquals(1, report.countOf(EventState.DUPLICATE))
        assertEquals(1, report.countOf(EventState.ACCEPTED))
        assertEquals(2, report.countOf(EventState.UNRATED))
        assertEquals(1, report.countOf(EventState.FAILED))
        assertEquals(1, report.countOf(EventState.QUARANTINED))
        assertEquals(5, report.countOf(EventState.RATED))
    }

    @Test
    fun `received counts rejections and duplicates too`() {
        // They arrived, so they must be accounted for -- otherwise the identity
        // "received = accepted + duplicates + rejected" could never hold.
        stubReport(received = 7, rejected = 2, duplicates = 1, ratedOpen = 7L to "10.0000")

        val report = TenantContext.runAs(tenant) { service.report(customer, august) }

        assertEquals(10, report.receivedCount)
        assertTrue(report.isBalanced)
    }

    @Test
    fun `splits rated transactions by whether the period is closed`() {
        stubReport(received = 8, ratedOpen = 3L to "30.0000", ratedClosed = 5L to "50.0000")

        val report = TenantContext.runAs(tenant) { service.report(customer, august) }

        assertEquals(3, report.countOf(EventState.RATED))
        assertEquals(5, report.countOf(EventState.INVOICED))
        assertEquals("80.0000", report.billedAmount.amount.toPlainString())
    }

    @Test
    fun `reports zero for a period with nothing in it`() {
        stubReport()

        val report = TenantContext.runAs(tenant) { service.report(customer, august) }

        assertEquals(0, report.receivedCount)
        assertTrue(report.billedAmount.isZero())
        assertTrue(report.isBalanced)
    }

    @Test
    fun `falls back to the default currency when nothing has been rated`() {
        stubReport(received = 1, outboxByStatus = mapOf("UNRATED" to 1L))

        val report = TenantContext.runAs(tenant) { service.report(customer, august) }

        assertEquals(usd, report.billedAmount.currency)
    }

    @Test
    fun `stamps the report with the injected clock`() {
        stubReport()

        val report = TenantContext.runAs(tenant) { service.report(customer, august) }

        assertEquals(now, report.generatedAt)
    }

    @Test
    fun `queries under the tenant in scope`() {
        // The tenant must come from TenantContext, never from a caller-supplied
        // parameter: verified by checking what actually reached the query.
        stubReport()

        TenantContext.runAs(TenantId("tenant-b")) { service.report(customer, august) }

        verify {
            jdbc.queryForObject(
                match<String> { it.contains("FROM raw_event") },
                Long::class.java,
                "tenant-b", customer.value, any(), any(),
            )
        }
    }

    @Test
    fun `refuses to report with no tenant in scope`() {
        assertFailsWith<MissingTenantException> { service.report(customer, august) }
    }

    @Test
    fun `refuses to list lines with no tenant in scope`() {
        assertFailsWith<MissingTenantException> { service.lines(customer, august) }
    }
}
