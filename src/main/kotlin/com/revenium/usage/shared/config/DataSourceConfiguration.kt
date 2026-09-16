package com.revenium.usage.shared.config

import com.revenium.usage.tenancy.TenantAwareDataSource
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * Wraps whatever DataSource the application ends up with, so every connection carries
 * the current tenant and row-level security can enforce isolation.
 *
 * A BeanPostProcessor rather than a replacement @Bean: this way the wrapper applies to
 * the DataSource Boot's autoconfiguration built, and equally to one supplied by
 * Testcontainers' @ServiceConnection, without this class having to know how it was
 * created or duplicating DataSourceProperties.
 *
 * Every route to the database is covered -- JPA, JdbcTemplate, Liquibase, the outbox
 * worker's native queries -- because they all resolve the same bean. A per-repository
 * approach would only cover the repositories someone remembered to change.
 */
@Configuration
class DataSourceConfiguration {

    @Bean
    fun tenantAwareDataSourcePostProcessor(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
            when {
                bean !is DataSource -> bean
                // Idempotent: never wrap a wrapper.
                bean is TenantAwareDataSource -> bean
                else -> TenantAwareDataSource(bean)
            }
    }
}
