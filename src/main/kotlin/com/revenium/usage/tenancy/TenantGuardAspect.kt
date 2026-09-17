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
 * any [TenantScoped] argument matches the tenant in scope. Applied at the service boundary,
 * where a unit of work begins and there is enough context for a useful error.
 *
 * Ordered ahead of Spring's transaction advice (which defaults to [Ordered.LOWEST_PRECEDENCE])
 * so a cross-tenant call is rejected before a pooled connection is taken — running after would
 * still be correct, but burning a connection per rejected call is a free denial-of-service vector.
 *
 * Self-invocation bypasses the proxy and this advice, and is not papered over with
 * `AopContext.currentProxy()`: annotated methods are genuine entry points, and the aspect is
 * the first line of defence rather than the isolation mechanism. `OutboxWorkerIntegrationTest`
 * ("rated transactions are invisible to other tenants") shows row-level security still
 * returning nothing when the aspect is out of the picture.
 * Likewise the [ThreadLocal] does not cross a thread pool — the outbox worker and
 * `TenantAwareTaskDecorator` re-establish the scope explicitly, never implicitly.
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
     * The argument annotated [TenantScoped], or `null` when the method declares none: many
     * operations derive the tenant entirely from the context.
     */
    private fun tenantArgumentOf(joinPoint: JoinPoint): TenantId? {
        val method = (joinPoint.signature as? MethodSignature)?.method ?: return null
        val annotations = method.parameterAnnotations

        for ((index, parameterAnnotations) in annotations.withIndex()) {
            if (parameterAnnotations.none { it is TenantScoped }) continue

            return when (val argument = joinPoint.args.getOrNull(index)) {
                is TenantId -> argument
                is String -> TenantId.ofNullable(argument)
                // Another type is a wiring mistake: fail rather than silently skipping the
                // check the annotation promises.
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
         * Ahead of transaction advice, but not [Ordered.HIGHEST_PRECEDENCE], leaving room for
         * infrastructure advice (tracing, metrics) that belongs further out.
         */
        const val ORDER: Int = Ordered.HIGHEST_PRECEDENCE + 100
    }
}
