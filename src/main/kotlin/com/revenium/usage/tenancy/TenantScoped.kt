package com.revenium.usage.tenancy

/**
 * Marks a method parameter carrying the tenant an operation targets.
 *
 * [TenantGuardAspect] compares it against the tenant in scope and rejects a mismatch,
 * which catches the case where a caller passes a tenant id taken from a request body
 * rather than from the authenticated context.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class TenantScoped
