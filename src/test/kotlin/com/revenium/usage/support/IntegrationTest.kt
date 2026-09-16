package com.revenium.usage.support

import org.junit.jupiter.api.Tag
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.support.TestPropertySourceUtils
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile

/**
 * Starts one PostgreSQL container for the whole integration suite and points the
 * application at it.
 *
 * Started once as a static field and stopped by Testcontainers' shutdown hook, rather
 * than once per test class, which would multiply the suite runtime for no extra
 * confidence.
 *
 * ### Why the application connects as `usage_app`
 *
 * `@ServiceConnection` would wire the application to the container's superuser, and **a
 * superuser bypasses row-level security unconditionally** — even with
 * `FORCE ROW LEVEL SECURITY`. The isolation tests would then have passed while proving
 * nothing. That was not hypothetical: the first run of this suite leaked tenant-a's rows
 * into a tenant-b query, and the cause was the connecting role, not the policies.
 *
 * So the roles mirror production exactly:
 *
 * - **Liquibase** connects as the container's owner, on its own URL, because it creates
 *   extensions and tables.
 * - **The application** connects as `usage_app`, which owns nothing and has
 *   `NOBYPASSRLS`, so the policies actually apply to it.
 *
 * ### Why an initializer rather than `@DynamicPropertySource`
 *
 * `@DynamicPropertySource` is only honoured on the test class itself, not on an
 * `@Import`ed `@TestConfiguration`. Declared there it is silently ignored — the
 * properties never reach the context, and the failure surfaces much later as an
 * authentication error against whatever defaults were left in place.
 */
class PostgresContainerInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {

    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
            applicationContext,
            "spring.datasource.url=${CONTAINER.jdbcUrl}",
            // Subject to RLS, exactly as in production.
            "spring.datasource.username=$APP_USER",
            "spring.datasource.password=$APP_PASSWORD",

            // Liquibase needs its own connection as the owner: it creates the
            // extensions and tables that the application only reads and writes.
            "spring.liquibase.url=${CONTAINER.jdbcUrl}",
            "spring.liquibase.user=${CONTAINER.username}",
            "spring.liquibase.password=${CONTAINER.password}",
        )
    }

    companion object {
        const val APP_USER = "usage_app"
        const val APP_PASSWORD = "usage_app"

        /**
         * Cleanup runs as the owner, outside the Spring context.
         *
         * Registering it as a bean would give the application two `DataSource`
         * candidates, and the one that bypasses row-level security could win.
         */
        @JvmStatic
        val CLEANER: DatabaseCleaner by lazy {
            DatabaseCleaner(CONTAINER.jdbcUrl, CONTAINER.username, CONTAINER.password)
        }

        @JvmStatic
        val CONTAINER: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("usage_rating")
                .withUsername("usage_owner")
                .withPassword("owner")
                // Mounted into the image's own init directory rather than passed to
                // withInitScript(), which fails with NoClassDefFoundError on commons-io
                // with this Testcontainers version. The image's native mechanism also
                // runs the script before the database accepts connections, which is the
                // ordering this needs: Hibernate opens a connection as `usage_app` while
                // building the EntityManagerFactory, before any migration could create
                // that role.
                .withCopyToContainer(
                    MountableFile.forClasspathResource("db/testcontainers-init.sql"),
                    "/docker-entrypoint-initdb.d/01-create-app-role.sql",
                )
                .apply { start() }
    }
}

/**
 * Marks a test that needs the full application context and a real database.
 *
 * Tagged `integration` so `./gradlew test` — and therefore the coverage gate — excludes
 * it. The gate measures unit-test coverage; letting full-context tests count would
 * inflate it with code that was merely touched rather than actually verified.
 */
@Tag("integration")
@SpringBootTest
@ContextConfiguration(initializers = [PostgresContainerInitializer::class])
annotation class IntegrationTest
