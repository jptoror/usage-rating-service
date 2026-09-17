package com.revenium.usage.tenancy

/**
 * Holds the tenant a unit of work is executing on behalf of. Populated by `TenantFilter` at the
 * HTTP boundary and from the claimed row in the outbox worker.
 *
 * A plain [ThreadLocal], deliberately not [InheritableThreadLocal]: a pooled thread would keep
 * the tenant it inherited on its first use and silently apply it to unrelated work. Async work
 * re-establishes the scope explicitly via [runAs].
 */
object TenantContext {

    private val holder = ThreadLocal<TenantId>()

    /** The current tenant, or `null` when running outside any tenant scope. */
    fun currentOrNull(): TenantId? = holder.get()

    /**
     * The current tenant.
     *
     * @throws MissingTenantException when none is in scope: failing loudly beats defaulting to
     * some tenant and corrupting their data.
     */
    fun current(): TenantId =
        holder.get() ?: throw MissingTenantException("No tenant in scope")

    /**
     * Runs [block] with [tenant] in scope. Restores the previous value rather than clearing, so
     * nesting is safe, and the `finally` keeps a pooled thread from carrying a tenant onward.
     */
    fun <T> runAs(tenant: TenantId, block: () -> T): T {
        val previous = holder.get()
        holder.set(tenant)
        return try {
            block()
        } finally {
            if (previous == null) holder.remove() else holder.set(previous)
        }
    }

    /** Sets the tenant for the current thread. Prefer [runAs]; this exists for the servlet filter. */
    fun set(tenant: TenantId) = holder.set(tenant)

    /** Clears the current thread's tenant. The filter must call this in a `finally`. */
    fun clear() = holder.remove()
}
