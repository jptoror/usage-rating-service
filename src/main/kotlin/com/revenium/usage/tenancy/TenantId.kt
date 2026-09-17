package com.revenium.usage.tenancy

/**
 * A tenant identifier.
 *
 * A value class rather than a raw [String] so the compiler can tell a tenant id from
 * a customer id. Passing one where the other is expected is a cross-tenant bug, and
 * it should not be expressible.
 */
@JvmInline
value class TenantId(val value: String) {
    init {
        require(value.isNotBlank()) { "tenantId must not be blank" }
        require(value.length <= MAX_LENGTH) { "tenantId must be at most $MAX_LENGTH characters" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 100

        fun ofNullable(value: String?): TenantId? =
            value?.takeIf { it.isNotBlank() }?.let { TenantId(it.trim()) }
    }
}
