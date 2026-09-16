package com.revenium.usage.shared.config

import com.revenium.usage.tenancy.TenantAwareTaskDecorator
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import java.time.Clock

/**
 * Cross-cutting beans.
 *
 * The [Clock] is a bean rather than a static call so that time-dependent behaviour --
 * late-arrival detection, backoff, future-timestamp validation -- is testable with a
 * fixed clock instead of depending on when the suite runs.
 */
@Configuration
class ClockConfiguration {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /**
     * Executor for `@Async` work, with the tenant carried across the thread boundary.
     *
     * The outbox worker does not depend on this -- it re-establishes the tenant from
     * each claimed row -- but any `@Async` method would silently lose tenant scope
     * without the decorator.
     */
    @Bean
    fun applicationTaskExecutor(builder: ThreadPoolTaskExecutorBuilder): TaskExecutor =
        builder.taskDecorator(TenantAwareTaskDecorator()).build()
}
