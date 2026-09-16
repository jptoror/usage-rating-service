package com.revenium.usage.tenancy

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TenantIdTest {

    @Test
    fun `accepts a normal identifier`() {
        assertEquals("tenant-a", TenantId("tenant-a").value)
    }

    @Test
    fun `rejects blank identifiers`() {
        assertFailsWith<IllegalArgumentException> { TenantId("") }
        assertFailsWith<IllegalArgumentException> { TenantId("   ") }
    }

    @Test
    fun `rejects an identifier longer than the column allows`() {
        TenantId("a".repeat(TenantId.MAX_LENGTH))                       // boundary: allowed
        assertFailsWith<IllegalArgumentException> {
            TenantId("a".repeat(TenantId.MAX_LENGTH + 1))               // boundary: rejected
        }
    }

    @Test
    fun `ofNullable returns null for absent or blank input`() {
        assertNull(TenantId.ofNullable(null))
        assertNull(TenantId.ofNullable(""))
        assertNull(TenantId.ofNullable("  "))
    }

    @Test
    fun `ofNullable trims surrounding whitespace`() {
        // A header value arriving as " tenant-a " must not become a different tenant
        // from "tenant-a", or isolation would depend on incidental whitespace.
        assertEquals(TenantId("tenant-a"), TenantId.ofNullable("  tenant-a  "))
    }
}
