package com.revenium.usage.shared.domain

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IdentifiersTest {

    private val uuid = "73d4e120-77d0-4f11-a6d2-f3b43b430d9c"

    @Test
    fun `parses a well formed event id`() {
        assertEquals(UUID.fromString(uuid), EventId.parse(uuid).value)
        assertEquals(UUID.fromString(uuid), EventId.parse("  $uuid  ").value)
    }

    @Test
    fun `parseOrNull returns null instead of throwing on bad input`() {
        // Validation collects failures rather than aborting on the first one, so the
        // parse step must be able to fail quietly.
        assertNull(EventId.parseOrNull(null))
        assertNull(EventId.parseOrNull(""))
        assertNull(EventId.parseOrNull("not-a-uuid"))
    }

    @Test
    fun `rejects a blank customer id`() {
        assertFailsWith<IllegalArgumentException> { CustomerId("") }
        assertFailsWith<IllegalArgumentException> { CustomerId("  ") }
    }

    @Test
    fun `rejects an over-long customer id rather than truncating`() {
        CustomerId("c".repeat(CustomerId.MAX_LENGTH))                     // boundary: allowed
        assertFailsWith<IllegalArgumentException> {
            CustomerId("c".repeat(CustomerId.MAX_LENGTH + 1))             // boundary: rejected
        }
    }

    @Test
    fun `rejects a blank or over-long transaction code`() {
        assertFailsWith<IllegalArgumentException> { TransactionCode("") }
        TransactionCode("C".repeat(TransactionCode.MAX_LENGTH))
        assertFailsWith<IllegalArgumentException> {
            TransactionCode("C".repeat(TransactionCode.MAX_LENGTH + 1))
        }
    }

    @Test
    fun `renders identifiers as their bare value`() {
        // These end up in log lines and error messages; a wrapper prefix would be noise.
        assertEquals("customer-42", CustomerId("customer-42").toString())
        assertEquals("CODE", TransactionCode("CODE").toString())
        assertEquals(uuid, EventId.parse(uuid).toString())
    }

    @Test
    fun `quantity rejects zero and negatives`() {
        assertFailsWith<IllegalArgumentException> { Quantity(BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { Quantity(BigDecimal("-1")) }
    }

    @Test
    fun `quantity rejects precision the column cannot hold`() {
        Quantity(BigDecimal("1.123456"))                                   // boundary: 6 dp allowed
        assertFailsWith<IllegalArgumentException> { Quantity(BigDecimal("1.1234567")) }
    }

    @Test
    fun `quantity normalises trailing zeros without losing value`() {
        // 2.000000 and 2 must be the same quantity; otherwise scale alone would change
        // a billed amount's provenance.
        assertEquals(0, Quantity.of(BigDecimal("2.000000")).value.compareTo(BigDecimal("2")))
        assertEquals(0, Quantity.of(5L).value.compareTo(BigDecimal("5")))
    }

    @Test
    fun `quantity of a whole number keeps a non-negative scale`() {
        // stripTrailingZeros turns 100 into 1E+2, whose scale is negative and which
        // Postgres would reject on a NUMERIC(19,6) column.
        val q = Quantity.of(BigDecimal("100"))
        assert(q.value.scale() >= 0)
        assertEquals("100", q.value.toPlainString())
    }

    @Test
    fun `unit price rejects a negative value but allows zero`() {
        UnitPrice(BigDecimal.ZERO)                     // a free transaction code is legitimate
        assertFailsWith<IllegalArgumentException> { UnitPrice(BigDecimal("-0.01")) }
    }

    @Test
    fun `renders money and prices in plain notation`() {
        // toPlainString, never scientific notation: "1E+2" on an invoice is unreadable.
        assertEquals("0.003333", UnitPrice(BigDecimal("0.003333")).toString())
        assertEquals("2", Quantity.of(2L).toString())
    }
}
