package com.revenium.usage.tenancy

/**
 * Holds the tenant a unit of work is executing on behalf of.
 *
 * Backed by a plain [ThreadLocal], deliberately **not** an [InheritableThreadLocal]:
 * implicit inheritance into a thread pool is worse than no inheritance at all,
 * because a pooled thread keeps whatever tenant it inherited on its first use and
 * then silently applies it to unrelated work. Asynchronous work re-establishes the
 * context explicitly instead, via [runAs].
 *
 * The context is populated at the HTTP boundary by `TenantFilter`, and in the outbox
 * worker from the tenant stored on the claimed row.
 */
object TenantContext {

    private val holder = ThreadLocal<TenantId>()

    /** The current tenant, or `null` when running outside any tenant scope. */
    fun currentOrNull(): TenantId? = holder.get()

    /**
     * The current tenant.
     *
     * @throws MissingTenantException when no tenant is in scope. Callers that reach
     * this point without a tenant have a bug: failing loudly beats defaulting to
     * some tenant and corrupting their data.
     */
    fun current(): TenantId =
        holder.get() ?: throw MissingTenantException("No tenant in scope")

    /**
     * Runs [block] with [tenant] in scope, restoring the previous value afterwards.
     *
     * Restores rather than clears, so nesting is safe: an inner scope cannot
     * silently erase the outer one. The `finally` is what keeps a pooled thread from
     * carrying a tenant into the next, unrelated task.
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
