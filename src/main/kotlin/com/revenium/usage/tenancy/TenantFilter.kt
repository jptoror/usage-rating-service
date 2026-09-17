package com.revenium.usage.tenancy

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Establishes the tenant scope for a request from the `X-Tenant-Id` header.
 *
 * **The header is the only source of tenant identity.** A `tenantId` in the request
 * body is treated as data to validate, never as identity: the body is fully controlled
 * by the caller, so trusting it would let anyone bill another tenant by editing a JSON
 * field. The ingestion service rejects a body whose `tenantId` disagrees with the header.
 *
 * In a production deployment the header would be set by an authenticating gateway, or
 * replaced by a claim read from a verified JWT. That swap is confined to this class —
 * everything downstream reads [TenantContext]. The simplification is stated in the README.
 *
 * Requests without the header are not rejected here. Actuator and OpenAPI endpoints are
 * legitimately tenant-less; the [RequiresTenant] aspect and row-level security reject
 * tenant-scoped work that arrives without a scope.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TenantFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val tenant = TenantId.ofNullable(request.getHeader(TENANT_HEADER))

        if (tenant == null) {
            filterChain.doFilter(request, response)
            return
        }

        // Always cleared: this thread returns to the container's pool and must not
        // carry a tenant into the next, unrelated request.
        TenantContext.set(tenant)
        try {
            filterChain.doFilter(request, response)
        } finally {
            TenantContext.clear()
        }
    }

    companion object {
        const val TENANT_HEADER = "X-Tenant-Id"
    }
}
