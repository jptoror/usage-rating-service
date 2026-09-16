package com.revenium.usage.tenancy

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TenantAwareTaskDecoratorTest {

    private val decorator = TenantAwareTaskDecorator()

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    @Test
    fun `carries the submitting thread's tenant into the task`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val seen = arrayOfNulls<TenantId>(1)

            // Captured here, on the submitting thread -- not inside the task, where
            // the pool thread's context would be empty or stale.
            val decorated = TenantContext.runAs(TenantId("tenant-a")) {
                decorator.decorate { seen[0] = TenantContext.currentOrNull() }
            }
            executor.submit(decorated).get(5, TimeUnit.SECONDS)

            assertEquals(TenantId("tenant-a"), seen[0])
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `clears the tenant on the pool thread after the task`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val decorated = TenantContext.runAs(TenantId("tenant-a")) {
                decorator.decorate { }
            }
            executor.submit(decorated).get(5, TimeUnit.SECONDS)

            // The next task on this same pooled thread must not inherit tenant-a.
            val leaked = executor.submit<TenantId?> { TenantContext.currentOrNull() }
            assertNull(leaked.get(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `returns the task unchanged when there is no tenant to carry`() {
        // No implicit default: work submitted unscoped stays unscoped and fails
        // later at its @RequiresTenant boundary, which is the intended outcome.
        val original = Runnable { }
        assertSame(original, decorator.decorate(original))
    }
}
