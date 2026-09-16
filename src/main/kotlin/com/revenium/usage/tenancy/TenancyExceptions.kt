package com.revenium.usage.tenancy

/** No tenant was in scope where one is required. Maps to HTTP 400. */
class MissingTenantException(message: String) : RuntimeException(message)

/**
 * An operation tried to act on a tenant other than the one in scope. Maps to HTTP 403.
 *
 * The message deliberately does not echo the requested tenant id back to the caller:
 * confirming which tenant identifiers exist is itself a small information leak.
 */
class CrossTenantAccessException(
    val requestedTenant: TenantId,
    val contextTenant: TenantId,
) : RuntimeException(
    "Operation on tenant '$requestedTenant' attempted while acting as '$contextTenant'"
)
