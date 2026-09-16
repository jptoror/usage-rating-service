package com.revenium.usage.shared.domain

import java.util.UUID

/**
 * The upstream integration's identifier for a usage event.
 *
 * This is the idempotency key: re-delivering the same [EventId] for a tenant must
 * never produce a second billable transaction.
 */
@JvmInline
value class EventId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun parse(raw: String): EventId = EventId(UUID.fromString(raw.trim()))
        fun parseOrNull(raw: String?): EventId? =
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { parse(it) }.getOrNull() }
    }
}

/** An opaque customer reference. This service does not own the customer catalogue. */
@JvmInline
value class CustomerId(val value: String) {
    init {
        require(value.isNotBlank()) { "customerId must not be blank" }
        require(value.length <= MAX_LENGTH) { "customerId must be at most $MAX_LENGTH characters" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 200
    }
}

/**
 * The kind of billable usage, e.g. `VEHICLE_REGISTRATION`.
 *
 * Together with the tenant it selects the pricing rule, so it is part of the
 * financial path rather than a free-form label.
 */
@JvmInline
value class TransactionCode(val value: String) {
    init {
        require(value.isNotBlank()) { "transactionCode must not be blank" }
        require(value.length <= MAX_LENGTH) { "transactionCode must be at most $MAX_LENGTH characters" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 100
    }
}
