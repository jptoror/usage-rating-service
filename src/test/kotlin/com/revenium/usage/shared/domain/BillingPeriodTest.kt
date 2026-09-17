package com.revenium.usage.shared.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BillingPeriodTest {

    private val august = BillingPeriod(YearMonth.of(2026, 8))

    @Test
    fun `derives the period from when the event occurred`() {
        assertEquals(august, BillingPeriod.of(Instant.parse("2026-08-15T14:22:31Z")))
    }

    @Test
    fun `the last instant of a month belongs to that month`() {
        // Boundary: 23:59:59.999Z on the 31st is still August.
        assertEquals(august, BillingPeriod.of(Instant.parse("2026-08-31T23:59:59.999Z")))
    }

    @Test
    fun `the first instant of a month belongs to the new month`() {
        // Boundary: one millisecond later is September, and belongs to exactly one
        // period. Half-open intervals are what make that true.
        assertEquals(
            BillingPeriod(YearMonth.of(2026, 9)),
            BillingPeriod.of(Instant.parse("2026-09-01T00:00:00Z")),
        )
    }

    @Test
    fun `contains is inclusive at the start and exclusive at the end`() {
        assertTrue(Instant.parse("2026-08-01T00:00:00Z") in august)
        assertTrue(Instant.parse("2026-08-31T23:59:59.999Z") in august)
        assertFalse(Instant.parse("2026-09-01T00:00:00Z") in august)
        assertFalse(Instant.parse("2026-07-31T23:59:59.999Z") in august)
    }

    @Test
    fun `start and end are UTC midnight boundaries`() {
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), august.start)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), august.end)
    }

    @Test
    fun `one period's end is the next period's start`() {
        // No gap and no overlap between consecutive periods: every instant lands in
        // exactly one.
        assertEquals(august.end, august.next().start)
        assertEquals(august.start, august.previous().end)
    }

    @Test
    fun `handles the year boundary`() {
        val december = BillingPeriod(YearMonth.of(2026, 12))
        assertEquals(BillingPeriod(YearMonth.of(2027, 1)), december.next())
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), december.end)
    }

    @Test
    fun `handles February in a leap year`() {
        val february = BillingPeriod(YearMonth.of(2028, 2))
        assertTrue(Instant.parse("2028-02-29T12:00:00Z") in february)
        assertEquals(Instant.parse("2028-03-01T00:00:00Z"), february.end)
    }

    @Test
    fun `persisted form is the first day of the month`() {
        assertEquals("2026-08-01", august.startDate.toString())
        assertEquals("2026-09-01", august.endDate.toString())
    }

    @Test
    fun `parses and renders the canonical string form`() {
        assertEquals(august, BillingPeriod.parse("2026-08"))
        assertEquals(august, BillingPeriod.parse("  2026-08  "))
        assertEquals("2026-08", august.toString())
    }

    @Test
    fun `orders chronologically`() {
        assertTrue(august < august.next())
        assertTrue(august > august.previous())
    }
}
