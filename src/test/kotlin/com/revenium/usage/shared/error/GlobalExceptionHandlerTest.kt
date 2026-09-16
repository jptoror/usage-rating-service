package com.revenium.usage.shared.error

import com.revenium.usage.tenancy.CrossTenantAccessException
import com.revenium.usage.tenancy.MissingTenantException
import com.revenium.usage.tenancy.TenantId
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `a missing tenant is a client error, not a server error`() {
        val problem = handler.handleMissingTenant(MissingTenantException("none"))

        assertEquals(HttpStatus.BAD_REQUEST.value(), problem.status)
        assertEquals("Missing tenant", problem.title)
        assertTrue(problem.type.toString().endsWith("missing-tenant"))
    }

    @Test
    fun `a missing tenant header is reported as a tenancy problem`() {
        val header = mockk<MissingRequestHeaderException> {
            every { headerName } returns "X-Tenant-Id"
        }

        assertEquals("Missing tenant", handler.handleMissingHeader(header).title)
    }

    @Test
    fun `any other missing header is reported as itself`() {
        val header = mockk<MissingRequestHeaderException> {
            every { headerName } returns "X-Correlation-Id"
        }

        val problem = handler.handleMissingHeader(header)
        assertEquals("Missing header", problem.title)
        assertTrue(problem.detail!!.contains("X-Correlation-Id"))
    }

    @Test
    fun `cross-tenant access is 403 and does not echo the tenant back`() {
        val problem = handler.handleCrossTenant(
            CrossTenantAccessException(TenantId("tenant-b"), TenantId("tenant-a"))
        )

        assertEquals(HttpStatus.FORBIDDEN.value(), problem.status)
        // Confirming which tenant identifiers exist is itself a small information leak.
        assertFalse(problem.detail!!.contains("tenant-b"))
    }

    @Test
    fun `a malformed body is 400`() {
        val problem = handler.handleUnreadableBody(
            HttpMessageNotReadableException("bad json", mockk(relaxed = true))
        )
        assertEquals(HttpStatus.BAD_REQUEST.value(), problem.status)
    }

    @Test
    fun `a malformed period is a client error, not a server error`() {
        // Found by running the service: `period=not-a-period` fell through to the
        // catch-all and was reported as 500, which tells an integrator to retry and an
        // operator to investigate -- neither of which is right for bad input.
        val problem = handler.handleMalformedParameter(
            java.time.format.DateTimeParseException("Text 'not-a-period' could not be parsed", "not-a-period", 0)
        )

        assertEquals(HttpStatus.BAD_REQUEST.value(), problem.status)
        // The offending value is named, so the caller can fix it.
        assertTrue(problem.detail!!.contains("not-a-period"))
    }

    @Test
    fun `an invalid domain value is a client error too`() {
        val problem = handler.handleMalformedParameter(IllegalArgumentException("customerId must not be blank"))
        assertEquals(HttpStatus.BAD_REQUEST.value(), problem.status)
    }

    @Test
    fun `closing an already-closed period is a conflict, not a server error`() {
        // A legitimate request that conflicts with current state: 409, not 400 or 500.
        val problem = handler.handleConflict(IllegalStateException("Period 2026-09 is already closed"))

        assertEquals(HttpStatus.CONFLICT.value(), problem.status)
        assertTrue(problem.detail!!.contains("already closed"))
    }

    @Test
    fun `an unexpected failure is 500 with no internal detail`() {
        val problem = handler.handleUnexpected(RuntimeException("NullPointerException at Foo.kt:42"))

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), problem.status)
        // Internal structure helps an attacker and helps an integrator not at all.
        assertFalse(problem.detail!!.contains("Foo.kt"))
    }
}
