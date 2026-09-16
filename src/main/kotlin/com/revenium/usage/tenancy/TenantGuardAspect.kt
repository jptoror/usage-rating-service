package com.revenium.usage.tenancy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * Enforces that [RequiresTenant] boundaries execute inside a tenant scope, and that
 * any [TenantScoped] argument matches the tenant in scope.
 *
 * **Pointcut.** `@annotation` covers individually annotated methods, `@within` covers
 * every public method of an annotated class. It is applied at the *service* boundary
 * rather than at repositories: that is where a unit of work begins, and where there is
 * enough context to produce a useful error.
 *
 * **Advice ordering.** Ordered ahead of Spring's transaction advice
 * (`@EnableTransactionManagement` defaults to [Ordered.LOWEST_PRECEDENCE]), so a
 * cross-tenant call is rejected before a transaction is opened and before the database
 * is touched at all. Running after would still be correct — the transaction would roll
 * back — but it would burn a pooled connection on every rejected call, which is a free
 * denial-of-service vector.
 *
 * **Self-invocation.** Spring AOP proxies the bean, so `this.annotatedMethod()` from
 * inside the same class bypasses the proxy entirely and this advice does not run. We do
 * not paper over it with `AopContext.currentProxy()` or load-time weaving; instead the
 * annotated methods are genuine entry points called from other beans, and
 * `TenantGuardAspectTest` proves both that the aspect fires through the proxy and that
 * row-level security still blocks the data when self-invocation skips it.
 *
 * **Async propagation.** A [ThreadLocal] does not cross a thread-pool boundary. The
 * outbox worker re-establishes the scope from the tenant on the claimed row via
 * [TenantContext.runAs]; `TenantAwareTaskDecorator` does the same for `@Async` work.
 * Nothing is inherited implicitly.
 */
@Aspect
@Component
@Order(TenantGuardAspect.ORDER)
class TenantGuardAspect {

    @Before("@annotation(com.revenium.usage.tenancy.RequiresTenant) || @within(com.revenium.usage.tenancy.RequiresTenant)")
    fun enforceTenantContext(joinPoint: JoinPoint) {
        val current = TenantContext.currentOrNull()
            ?: throw MissingTenantException(
                "No tenant in scope at ${joinPoint.signature.toShortString()}"
            )

        tenantArgumentOf(joinPoint)?.let { requested ->
            if (requested != current) {
                log.warn {
                    "Cross-tenant access blocked at ${joinPoint.signature.toShortString()}: " +
                        "context=$current requested=$requested"
                }
                throw CrossTenantAccessException(requested, current)
            }
        }
    }

    /**
     * Extracts the argument annotated [TenantScoped], if the method declares one.
     *
     * Returns `null` when the method has no such parameter: many operations derive the
     * tenant entirely from the context, and requiring an explicit parameter everywhere
     * would be noise.
     */
    private fun tenantArgumentOf(joinPoint: JoinPoint): TenantId? {
        val method = (joinPoint.signature as? MethodSignature)?.method ?: return null
        val annotations = method.parameterAnnotations

        for ((index, parameterAnnotations) in annotations.withIndex()) {
            if (parameterAnnotations.none { it is TenantScoped }) continue

            return when (val argument = joinPoint.args.getOrNull(index)) {
                is TenantId -> argument
                is String -> TenantId.ofNullable(argument)
                // A @TenantScoped parameter of some other type is a wiring mistake.
                // Fail rather than silently skipping the check that annotation promises.
                null -> null
                else -> throw IllegalStateException(
                    "@TenantScoped parameter at index $index of " +
                        "${joinPoint.signature.toShortString()} must be TenantId or String, " +
                        "but was ${argument::class.simpleName}"
                )
            }
        }
        return null
    }

    companion object {
        /**
         * Ahead of transaction advice, which sits at [Ordered.LOWEST_PRECEDENCE].
         * Not [Ordered.HIGHEST_PRECEDENCE] itself, to leave room for infrastructure
         * advice (tracing, metrics) that legitimately belongs further out.
         */
        const val ORDER: Int = Ordered.HIGHEST_PRECEDENCE + 100
    }
}
