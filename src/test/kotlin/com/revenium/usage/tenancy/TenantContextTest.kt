package com.revenium.usage.tenancy

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TenantContextTest {

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    @Test
    fun `has no tenant by default`() {
        assertNull(TenantContext.currentOrNull())
    }

    @Test
    fun `current throws rather than defaulting to some tenant`() {
        // Failing loudly is the point: defaulting would write one tenant's data
        // under another's id.
        assertFailsWith<MissingTenantException> { TenantContext.current() }
    }

    @Test
    fun `runAs makes the tenant visible inside the block`() {
        val seen = TenantContext.runAs(TenantId("tenant-a")) { TenantContext.current() }
        assertEquals(TenantId("tenant-a"), seen)
    }

    @Test
    fun `runAs clears the scope afterwards`() {
        TenantContext.runAs(TenantId("tenant-a")) { }
        assertNull(TenantContext.currentOrNull())
    }

    @Test
    fun `runAs restores the outer tenant rather than clearing it`() {
        TenantContext.runAs(TenantId("outer")) {
            TenantContext.runAs(TenantId("inner")) {
                assertEquals(TenantId("inner"), TenantContext.current())
            }
            // The inner scope must not erase the outer one: an operation that
            // briefly acts for another tenant would otherwise continue unscoped.
            assertEquals(TenantId("outer"), TenantContext.current())
        }
    }

    @Test
    fun `runAs restores the scope even when the block throws`() {
        assertFailsWith<IllegalStateException> {
            TenantContext.runAs(TenantId("tenant-a")) { error("boom") }
        }
        assertNull(TenantContext.currentOrNull())
    }

    @Test
    fun `tenant does not leak between threads`() {
        // The property that makes a plain ThreadLocal correct here: a tenant set on
        // one thread must be invisible to another, or pooled threads would cross
        // tenants.
        val executor = Executors.newSingleThreadExecutor()
        try {
            TenantContext.set(TenantId("tenant-a"))
            val seenOnOtherThread = executor.submit<TenantId?> { TenantContext.currentOrNull() }
            assertNull(seenOnOtherThread.get(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent scopes do not interfere`() {
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..8).map { n ->
                executor.submit<Pair<String, String>> {
                    start.await()
                    val expected = "tenant-$n"
                    // Each thread must observe exactly its own tenant, whatever the
                    // others are doing at the same moment.
                    TenantContext.runAs(TenantId(expected)) {
                        Thread.sleep(5)
                        expected to TenantContext.current().value
                    }
                }
            }
            start.countDown()
            results.forEach { future ->
                val (expected, actual) = future.get(5, TimeUnit.SECONDS)
                assertEquals(expected, actual)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
