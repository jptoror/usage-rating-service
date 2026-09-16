package com.revenium.usage.support

import java.sql.DriverManager

/**
 * Empties the tenant-scoped tables between integration tests.
 *
 * ### Why a plain JDBC connection rather than a Spring bean
 *
 * Cleanup has to run as the database **owner**: the application connects as `usage_app`,
 * which has `NOBYPASSRLS`, so a `DELETE` through it is scoped to one tenant. Rows
 * belonging to a tenant the test class does not know about survive and then block the
 * foreign key on `raw_event` — which is how this failed in CI while passing locally.
 *
 * But registering a second `DataSource` bean is worse than the problem. The application's
 * `BeanPostProcessor` wraps every `DataSource` it finds, and with two candidates the
 * application can resolve the owner's — silently bypassing row-level security and making
 * the isolation tests pass while proving nothing. That happened, and it turned three
 * green tenant tests red for the right reason.
 *
 * So this opens its own connection directly, invisible to the context. Nothing else can
 * accidentally resolve it.
 *
 * `pricing_rule` is left alone: it is seed data every test depends on.
 */
class DatabaseCleaner(
    private val jdbcUrl: String,
    private val ownerUser: String,
    private val ownerPassword: String,
) {

    /** Deletes every row from the tenant-scoped tables, children before parents. */
    fun clear() {
        DriverManager.getConnection(jdbcUrl, ownerUser, ownerPassword).use { connection ->
            connection.createStatement().use { statement ->
                TABLES_CHILDREN_FIRST.forEach { table ->
                    statement.executeUpdate("DELETE FROM $table")
                }
            }
        }
    }

    private companion object {
        /**
         * Foreign-key order: `invoice_line` → `invoice`, and `rated_transaction`,
         * `outbox_message` and `event_conflict` all → `raw_event`.
         */
        val TABLES_CHILDREN_FIRST = listOf(
            "invoice_line",
            "invoice",
            "rated_transaction",
            "outbox_message",
            "event_conflict",
            "rejected_event",
            "raw_event",
        )
    }
}
