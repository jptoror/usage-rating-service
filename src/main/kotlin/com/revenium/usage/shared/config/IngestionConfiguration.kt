package com.revenium.usage.shared.config

import com.revenium.usage.ingestion.domain.model.TransactionValidator
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("ingestion")
data class IngestionProperties(
    /**
     * How far into the future an `occurredAt` may be before it is rejected.
     *
     * Tolerates ordinary clock skew between the sender and this service. A timestamp
     * beyond it would select a pricing rule not yet in effect and land in a billing
     * period that has not started.
     */
    val maxFutureSkew: Duration = Duration.ofMinutes(5),
)

/**
 * Wires the ingestion domain, which carries no Spring annotations of its own.
 *
 * `TransactionValidator` is constructed here rather than component-scanned so that its
 * configuration arrives as an explicit argument. That keeps the class framework-free --
 * it unit-tests with a fixed clock and no context -- and avoids the default-parameter
 * pitfall that once stopped this application from starting.
 */
@Configuration
@EnableConfigurationProperties(IngestionProperties::class)
class IngestionConfiguration {

    @Bean
    fun transactionValidator(clock: Clock, properties: IngestionProperties): TransactionValidator =
        TransactionValidator(clock, properties.maxFutureSkew)
}
