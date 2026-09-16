package com.revenium.usage.tenancy

/**
 * Marks a service boundary that must execute inside a tenant scope.
 *
 * Applied to a class, every public method is covered. [TenantGuardAspect] enforces it.
 *
 * Two limits are worth knowing before relying on this:
 *
 * 1. **Self-invocation.** Spring AOP is proxy-based, so calling an annotated method
 *    through `this` from inside the same class does not pass through the proxy and
 *    the aspect never runs. This is proven by a test rather than assumed away.
 * 2. **It is not the isolation mechanism.** It fails fast with a clear error at the
 *    service boundary. Actual isolation is enforced by repository queries and by
 *    row-level security in PostgreSQL, which hold even when this aspect does not run.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class RequiresTenant
