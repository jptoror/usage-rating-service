package com.revenium.usage.tenancy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import javax.sql.DataSource

private val log = KotlinLogging.logger {}

/**
 * Applies the current tenant to the database session so PostgreSQL row-level security
 * can enforce it.
 *
 * This is the link between the application's notion of "who am I acting for" and the
 * database's. Without it the RLS policies see an unset `app.tenant_id`, evaluate to
 * NULL, and every query returns nothing — failing closed, which is the correct default
 * but not a working application.
 *
 * `SET LOCAL` is scoped to the surrounding transaction and is discarded at commit or
 * rollback. That property is what makes this safe with a connection pool: a setting
 * that outlived the transaction would be applied to whichever request borrowed the
 * connection next, which is precisely the cross-tenant leak this design exists to
 * prevent. It also means this must be called **inside** a transaction; outside one,
 * `SET LOCAL` is silently discarded.
 *
 * The tenant id is bound as a parameter rather than interpolated, so a hostile tenant
 * id cannot alter the statement.
 */
@Component
class TenantConnectionPreparer(private val dataSource: DataSource) {

    /** Applies [tenant] to the current transaction's connection. */
    fun applyTo(tenant: TenantId) {
        dataSource.connection.use { connection ->
            check(!connection.autoCommit) {
                "applyTo must be called inside a transaction; SET LOCAL is discarded otherwise"
            }
            connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)").use { statement ->
                statement.setString(1, tenant.value)
                statement.execute()
            }
        }
        log.trace { "Applied tenant $tenant to database session" }
    }

    /** Applies the tenant currently in scope, if there is one. */
    fun applyCurrent() {
        TenantContext.currentOrNull()?.let(::applyTo)
    }
}
