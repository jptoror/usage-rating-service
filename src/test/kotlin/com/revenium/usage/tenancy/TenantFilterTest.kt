package com.revenium.usage.tenancy

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TenantFilterTest {

    private val filter = TenantFilter()
    private val response = MockHttpServletResponse()

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    // A real MockHttpServletRequest rather than a relaxed mock: OncePerRequestFilter
    // checks getAttribute() to detect re-entry, and a relaxed mock returns a non-null
    // object for it, which makes the filter skip its own body and silently pass the test.
    private fun requestWithHeader(value: String?): HttpServletRequest =
        MockHttpServletRequest().apply {
            value?.let { addHeader(TenantFilter.TENANT_HEADER, it) }
        }

    /** Captures what the tenant scope looked like *during* the downstream call. */
    private fun chainCapturingTenant(captured: MutableList<TenantId?>): FilterChain =
        mockk<FilterChain> {
            every { doFilter(any(), any()) } answers {
                captured += TenantContext.currentOrNull()
                Unit
            }
        }

    @Test
    fun `establishes the tenant scope for the downstream chain`() {
        val seen = mutableListOf<TenantId?>()
        filter.doFilter(requestWithHeader("tenant-a"), response, chainCapturingTenant(seen))

        assertEquals(listOf<TenantId?>(TenantId("tenant-a")), seen)
    }

    @Test
    fun `clears the scope after the request so a pooled thread cannot carry it`() {
        filter.doFilter(requestWithHeader("tenant-a"), response, mockk(relaxed = true))
        assertNull(TenantContext.currentOrNull())
    }

    @Test
    fun `clears the scope even when the chain throws`() {
        val exploding = mockk<FilterChain> {
            every { doFilter(any(), any()) } throws IllegalStateException("downstream failure")
        }

        runCatching { filter.doFilter(requestWithHeader("tenant-a"), response, exploding) }

        assertNull(TenantContext.currentOrNull())
    }

    @Test
    fun `passes the request through untouched when the header is absent`() {
        // Actuator and OpenAPI are legitimately tenant-less. Rejecting here would
        // break them; tenant-scoped work is rejected by the aspect and by RLS.
        val seen = mutableListOf<TenantId?>()
        val chain = chainCapturingTenant(seen)

        filter.doFilter(requestWithHeader(null), response, chain)

        assertEquals(listOf<TenantId?>(null), seen)
        verify(exactly = 1) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `treats a blank header as absent`() {
        val seen = mutableListOf<TenantId?>()
        filter.doFilter(requestWithHeader("   "), response, chainCapturingTenant(seen))

        assertEquals(listOf<TenantId?>(null), seen)
    }

    @Test
    fun `trims whitespace around the header value`() {
        val seen = mutableListOf<TenantId?>()
        filter.doFilter(requestWithHeader("  tenant-a  "), response, chainCapturingTenant(seen))

        assertEquals(listOf<TenantId?>(TenantId("tenant-a")), seen)
    }
}
