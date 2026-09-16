package com.revenium.usage.shared.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * A calendar month in UTC, the unit an invoice covers.
 *
 * The interval is half-open — `[start, end)` — exactly like pricing rule validity, and
 * for the same reason: an event at `2026-08-31T23:59:59.999Z` belongs to August and one
 * at `2026-09-01T00:00:00.000Z` belongs to September, with no instant belonging to both
 * or to neither.
 */
@JvmInline
value class BillingPeriod(val yearMonth: YearMonth) : Comparable<BillingPeriod> {

    /** First instant of the period, inclusive. */
    val start: Instant get() = yearMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    /** First instant of the following period, exclusive. */
    val end: Instant get() = yearMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    /** Persisted form: the first day of the month. */
    val startDate: LocalDate get() = yearMonth.atDay(1)

    val endDate: LocalDate get() = yearMonth.plusMonths(1).atDay(1)

    operator fun contains(instant: Instant): Boolean =
        !instant.isBefore(start) && instant.isBefore(end)

    fun next(): BillingPeriod = BillingPeriod(yearMonth.plusMonths(1))

    fun previous(): BillingPeriod = BillingPeriod(yearMonth.minusMonths(1))

    override fun compareTo(other: BillingPeriod): Int = yearMonth.compareTo(other.yearMonth)

    override fun toString(): String = yearMonth.toString()

    companion object {
        /** The period an event belongs to, derived from when it occurred. */
        fun of(occurredAt: Instant): BillingPeriod =
            BillingPeriod(YearMonth.from(occurredAt.atZone(ZoneOffset.UTC)))

        fun of(date: LocalDate): BillingPeriod = BillingPeriod(YearMonth.from(date))

        /** Parses `2026-08`. */
        fun parse(raw: String): BillingPeriod = BillingPeriod(YearMonth.parse(raw.trim()))
    }
}
