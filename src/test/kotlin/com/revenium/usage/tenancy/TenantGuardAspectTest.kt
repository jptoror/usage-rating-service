package com.revenium.usage.tenancy

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Proves the aspect's behaviour — and its documented limitation — without a Spring
 * context, by building the same kind of AOP proxy Spring would create.
 */
class TenantGuardAspectTest {

    private fun <T : Any> proxy(target: T): T {
        val factory = AspectJProxyFactory(target)
        factory.addAspect(TenantGuardAspect())
        factory.isProxyTargetClass = true
        return factory.getProxy()
    }

    @AfterEach
    fun cleanUp() = TenantContext.clear()

    // --- the aspect fires --------------------------------------------------

    @Test
    fun `rejects a call made with no tenant in scope`() {
        val service = proxy(GuardedService())
        assertFailsWith<MissingTenantException> { service.readSomething() }
    }

    @Test
    fun `allows a call made inside a tenant scope`() {
        val service = proxy(GuardedService())
        val result = TenantContext.runAs(TenantId("tenant-a")) { service.readSomething() }
        assertEquals("read", result)
    }

    @Test
    fun `rejects an argument naming a different tenant`() {
        val service = proxy(GuardedService())
        // The attack this blocks: a caller passing a tenant id lifted from the
        // request body while authenticated as someone else.
        val failure = assertFailsWith<CrossTenantAccessException> {
            TenantContext.runAs(TenantId("tenant-a")) {
                service.readForTenant(TenantId("tenant-b"))
            }
        }
        assertEquals(TenantId("tenant-b"), failure.requestedTenant)
        assertEquals(TenantId("tenant-a"), failure.contextTenant)
    }

    @Test
    fun `allows an argument naming the tenant in scope`() {
        val service = proxy(GuardedService())
        val result = TenantContext.runAs(TenantId("tenant-a")) {
            service.readForTenant(TenantId("tenant-a"))
        }
        assertEquals("read:tenant-a", result)
    }

    @Test
    fun `accepts a tenant argument declared as a String`() {
        val service = proxy(GuardedService())
        assertFailsWith<CrossTenantAccessException> {
            TenantContext.runAs(TenantId("tenant-a")) { service.readForTenantString("tenant-b") }
        }
    }

    @Test
    fun `guards every public method of an annotated class`() {
        // @within, not just @annotation: a new method on a guarded class is covered
        // by default rather than only when someone remembers the annotation.
        val service = proxy(GuardedClassLevelService())
        assertFailsWith<MissingTenantException> { service.methodWithoutItsOwnAnnotation() }
    }

    @Test
    fun `rejects a TenantScoped parameter of an unsupported type`() {
        val service = proxy(GuardedService())
        // A wiring mistake must fail loudly, not silently skip the check the
        // annotation promises.
        assertFailsWith<IllegalStateException> {
            TenantContext.runAs(TenantId("tenant-a")) { service.readForTenantWrongType(42) }
        }
    }

    @Test
    fun `ignores an unannotated method on an unannotated class`() {
        val service = proxy(GuardedService())
        assertEquals("open", service.unguarded())
    }

    // --- the documented limitation ----------------------------------------

    @Test
    fun `self-invocation bypasses the proxy and the aspect does not run`() {
        val service = proxy(SelfInvokingService())

        // Called through the proxy: guarded, as expected.
        assertFailsWith<MissingTenantException> { service.guarded() }

        // Reached via `this` from inside the same class: the call never leaves the
        // target object, so no proxy and no advice. This is a real Spring AOP
        // limitation, proven here rather than assumed away.
        //
        // It is also why the aspect is not the isolation mechanism: row-level security
        // still returns nothing for such a call. This test uses no database, so the
        // proof lives in IngestionIntegrationTest ("one tenant cannot read another's
        // events") and OutboxWorkerIntegrationTest ("rated transactions are invisible
        // to other tenants").
        assertEquals("guarded", service.viaSelfInvocation())
    }
}

// --- fixtures -------------------------------------------------------------

open class GuardedService {
    @RequiresTenant
    open fun readSomething(): String = "read"

    @RequiresTenant
    open fun readForTenant(@TenantScoped tenant: TenantId): String = "read:${tenant.value}"

    @RequiresTenant
    open fun readForTenantString(@TenantScoped tenant: String): String = "read:$tenant"

    @RequiresTenant
    open fun readForTenantWrongType(@TenantScoped tenant: Int): String = "read:$tenant"

    open fun unguarded(): String = "open"
}

@RequiresTenant
open class GuardedClassLevelService {
    open fun methodWithoutItsOwnAnnotation(): String = "guarded"
}

open class SelfInvokingService {
    @RequiresTenant
    open fun guarded(): String = "guarded"

    /** Calls [guarded] through `this`, which does not pass through the proxy. */
    open fun viaSelfInvocation(): String = guarded()
}
