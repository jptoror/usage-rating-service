package com.revenium.usage.tenancy

import org.springframework.jdbc.datasource.DelegatingDataSource
import java.sql.Connection
import java.sql.Types
import javax.sql.DataSource

/**
 * Stamps the current tenant onto every connection the pool hands out.
 *
 * This is what makes row-level security work at runtime: the policies compare `tenant_id`
 * against `current_setting('app.tenant_id')`, so without this every query matches nothing.
 *
 * Wrapping the [DataSource] covers **every** route to the database — JPA, `JdbcTemplate`,
 * Liquibase, the worker's native queries. A per-repository approach would cover only the
 * repositories someone remembered.
 */
class TenantAwareDataSource(delegate: DataSource) : DelegatingDataSource(delegate) {

    override fun getConnection(): Connection = applyTenant(super.getConnection())

    override fun getConnection(username: String, password: String): Connection =
        applyTenant(super.getConnection(username, password))

    /**
     * Writes the tenant — or clears it — on [connection], always.
     *
     * Returning early when there is no tenant would leave the *previous* borrower's
     * tenant on the pooled connection for the next caller to inherit. That was a real
     * bug: the worker's unscoped claim saw only one tenant's rows.
     */
    private fun applyTenant(connection: Connection): Connection {
        val tenant = TenantContext.currentOrNull()

        try {
            connection.prepareStatement(SET_TENANT_SQL).use { statement ->
                // Bound, never interpolated: this statement enforces isolation, so it
                // must not be constructible from input.
                if (tenant == null) {
                    statement.setNull(1, Types.VARCHAR)
                } else {
                    statement.setString(1, tenant.value)
                }
                statement.execute()
            }
        } catch (e: Exception) {
            // An unscoped connection must never escape: it would see nothing, or — with
            // a privileged role — everything.
            connection.close()
            throw IllegalStateException("Failed to apply tenant scope to the connection", e)
        }
        return connection
    }

    private companion object {
        /**
         * `is_local => false` is session scope, deliberately, not `SET LOCAL`.
         *
         * This runs when the connection is handed out, which happens *before* Spring
         * opens the transaction — so a transaction-scoped setting would be discarded by
         * the first commit, leaving every later statement on that connection unscoped.
         *
         * Session scope is safe because the value is rewritten on every checkout, and
         * cleared when there is no tenant.
         */
        const val SET_TENANT_SQL = "SELECT set_config('app.tenant_id', ?, false)"
    }
}
