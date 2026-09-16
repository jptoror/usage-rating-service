package com.revenium.usage.tenancy

import org.springframework.core.task.TaskDecorator

/**
 * Carries the submitting thread's tenant into an `@Async` task.
 *
 * The tenant is captured **at submission time**, on the calling thread, and applied
 * around the task body on the executing thread. Capturing it inside the task would
 * read the pool thread's context, which is either empty or — worse — left over from
 * whatever ran there previously.
 *
 * A task submitted with no tenant in scope runs with none, rather than inheriting one.
 * Tenant-scoped work will then fail fast at its [RequiresTenant] boundary, which is the
 * intended outcome: silently guessing a tenant is how cross-tenant corruption happens.
 *
 * The outbox worker does not rely on this. It re-establishes the scope from the
 * `tenant_id` stored on each claimed row, so a message processed minutes later by a
 * different instance still runs under the right tenant.
 */
class TenantAwareTaskDecorator : TaskDecorator {

    override fun decorate(runnable: Runnable): Runnable {
        val captured = TenantContext.currentOrNull() ?: return runnable
        return Runnable { TenantContext.runAs(captured) { runnable.run() } }
    }
}
