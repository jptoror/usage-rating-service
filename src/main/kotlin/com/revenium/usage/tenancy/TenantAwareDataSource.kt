package com.revenium.usage.tenancy

import org.springframework.jdbc.datasource.DelegatingDataSource
import java.sql.Connection
import javax.sql.DataSource

/**
 * Applies the current tenant to every connection handed out by the pool.
 *
 * This is what makes row-level security actually work at runtime. The policies compare
 * `tenant_id` against `current_setting('app.tenant_id')`, so unless something sets that
 * value on the session, every query matches nothing.
 *
 * Wrapping the [DataSource] rather than hooking Hibernate or an interceptor is
 * deliberate: **every** path to the database goes through here — JPA, `JdbcTemplate`,
 * Liquibase, the outbox worker's native queries — so there is no route that quietly
 * bypasses the mechanism. A per-repository approach would only cover the repositories
 * someone remembered.
 *
 * `set_config(..., is_local => true)` is the function form of `SET LOCAL`: scoped to the
 * current transaction and discarded at commit or rollback. That is what keeps a pooled
 * connection from carrying one request's tenant into the next. Outside a transaction it
 * has no lasting effect, which is why tenant-scoped work is always transactional.
 *
 * A connection requested with no tenant in scope has the setting explicitly **cleared**,
 * not merely left alone: leaving it alone would hand the previous borrower's tenant to
 * the next caller. With it cleared, the policies match nothing, which fails closed.
 */
class TenantAwareDataSource(delegate: DataSource) : DelegatingDataSource(delegate) {

    override fun getConnection(): Connection = applyTenant(super.getConnection())

    override fun getConnection(username: String, password: String): Connection =
        applyTenant(super.getConnection(username, password))

    private fun applyTenant(connection: Connection): Connection {
        // ALWAYS written, including when there is no tenant -- in which case it is
        // written as NULL to clear it.
        //
        // Returning early on a null tenant would leave the PREVIOUS borrower's tenant on
        // the connection, and the next unscoped caller would silently inherit it. That is
        // exactly the cross-request leak row-level security exists to prevent, and it was
        // a real bug here: the outbox worker's unscoped claim saw only one tenant's rows,
        // because the connection it borrowed still carried the tenant of whoever used it
        // last.
        val tenant = TenantContext.currentOrNull()

        try {
            connection.prepareStatement(SET_TENANT_SQL).use { statement ->
                // Bound, never interpolated: this statement is the mechanism that
                // enforces isolation, so it must not be constructible from input.
                //
                // set_config(..., NULL) leaves the setting NULL, which every policy
                // treats as "no tenant" and therefore matches nothing. Failing closed.
                if (tenant == null) {
                    statement.setNull(1, java.sql.Types.VARCHAR)
                } else {
                    statement.setString(1, tenant.value)
                }
                statement.execute()
            }
        } catch (e: Exception) {
            // A connection that could not be scoped must not be used: it would either
            // see nothing (confusing) or, with a privileged role, see everything.
            connection.close()
            throw IllegalStateException("Failed to apply tenant scope to the connection", e)
        }
        return connection
    }

    private companion object {
        /**
         * `is_local => false`: session scope, not transaction scope.
         *
         * The obvious choice is `SET LOCAL` (`is_local => true`), which the database
         * discards at commit. But this runs when the connection is *handed out*, which
         * happens before Spring begins the transaction — so a transaction-scoped setting
         * would be discarded by the very first commit and leave every later statement on
         * that connection unscoped.
         *
         * Session scope is safe here because the setting is rewritten on every checkout
         * from the pool, and a checkout with no tenant in scope leaves the previous
         * value in place only for work that is itself unscoped and therefore not
         * tenant-sensitive. [TenantConnectionPreparer] still uses `SET LOCAL` for the
         * case where a tenant is applied inside an already-open transaction.
         */
        const val SET_TENANT_SQL = "SELECT set_config('app.tenant_id', ?, false)"
    }
}
