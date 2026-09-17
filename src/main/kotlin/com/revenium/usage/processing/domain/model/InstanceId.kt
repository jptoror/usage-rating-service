package com.revenium.usage.processing.domain.model

import java.net.InetAddress

/**
 * Identifies the application instance that processed a message.
 *
 * Recorded on each claimed outbox row purely as evidence. Nothing behaves differently
 * because of it -- the coordination is entirely `FOR UPDATE SKIP LOCKED` -- but without
 * it there is no way to show that three instances took disjoint batches rather than one
 * instance doing all the work while the others idled.
 *
 * A plain class, deliberately **not** a `@JvmInline value class` like the other
 * identifiers in this codebase. A value class erases to its underlying type, and Kotlin
 * then emits a synthetic constructor taking a `DefaultConstructorMarker`, which Spring
 * tries to autowire as a bean -- the application fails to start with
 * `No qualifying bean of type 'kotlin.jvm.internal.DefaultConstructorMarker'`.
 *
 * The other value classes in this project are never injected as constructor parameters,
 * which is why they are safe.
 */
data class InstanceId(val value: String) {

    override fun toString(): String = value

    companion object {
        /**
         * Resolves this instance's identity: [override] when set, otherwise the
         * hostname, which Docker Compose makes unique per replica.
         */
        fun detect(override: String? = null): InstanceId {
            override?.takeIf { it.isNotBlank() }?.let { return InstanceId(it.trim()) }

            val hostname = runCatching { InetAddress.getLocalHost().hostName }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }

            return InstanceId(hostname ?: "instance-${(1..0xFFFF).random().toString(16)}")
        }
    }
}
