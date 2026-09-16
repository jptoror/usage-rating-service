package com.revenium.usage.shared.config

import com.revenium.usage.pricing.domain.PricingRuleLookup
import com.revenium.usage.processing.application.OutboxProperties
import com.revenium.usage.rating.domain.RatingCalculator
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@ConfigurationProperties("billing")
data class BillingProperties(
    /**
     * Currency used when a period has no rated transactions to take one from.
     *
     * Only reached for an empty period: any transaction carries the currency of the
     * pricing rule that priced it.
     */
    val defaultCurrency: String = "USD",
)

@ConfigurationProperties("billing.late-arrival")
data class LateArrivalProperties(
    /**
     * How long after the usage occurred an event may still arrive and be billed
     * automatically. Beyond this it is quarantined for a human, because an event that
     * old almost always means an accidental replay.
     */
    val maxAge: Duration = Duration.ofDays(90),
)

@Configuration
@EnableConfigurationProperties(
    OutboxProperties::class,
    LateArrivalProperties::class,
    BillingProperties::class,
)
class RatingConfiguration {

    /**
     * The rating calculator is a plain constructed object, not a component-scanned bean.
     *
     * It has no framework dependencies at all, which is what lets its boundary cases be
     * tested as ordinary unit tests. Wiring it here keeps that property while still
     * letting the cutoff come from configuration.
     */
    @Bean
    fun ratingCalculator(
        pricingRules: PricingRuleLookup,
        lateArrival: LateArrivalProperties,
    ): RatingCalculator = RatingCalculator(pricingRules, lateArrival.maxAge)

    @Bean
    fun defaultCurrency(billing: BillingProperties): java.util.Currency =
        java.util.Currency.getInstance(billing.defaultCurrency)
}
