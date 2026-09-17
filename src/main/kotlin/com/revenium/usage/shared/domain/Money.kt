package com.revenium.usage.shared.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * A billable quantity. [BigDecimal] rather than [Int]: usage is not always whole units, and a
 * quantity that silently truncates bills the wrong amount.
 */
@JvmInline
value class Quantity(val value: BigDecimal) {
    init {
        require(value.signum() > 0) { "quantity must be positive, was $value" }
        require(value.scale() <= SCALE) { "quantity scale must be at most $SCALE, was ${value.scale()}" }
    }

    override fun toString(): String = value.toPlainString()

    companion object {
        const val SCALE = 6
        val ONE = Quantity(BigDecimal.ONE)

        fun of(value: BigDecimal): Quantity = Quantity(value.stripTrailingZeros().let {
            if (it.scale() < 0) it.setScale(0) else it
        })

        fun of(value: Long): Quantity = Quantity(BigDecimal.valueOf(value))
    }
}

/**
 * A price per unit, kept at [SCALE] digits and never rounded: rounding it would compound the
 * error across every transaction that uses it. Only amounts are rounded, and only once.
 */
@JvmInline
value class UnitPrice(val value: BigDecimal) {
    init {
        require(value.signum() >= 0) { "unitPrice must not be negative, was $value" }
    }

    override fun toString(): String = value.toPlainString()

    companion object {
        const val SCALE = 6
    }
}

/**
 * A monetary amount with its currency, held at [SCALE] and rounded [HALF_UP] exactly once when
 * produced. Arithmetic here never rounds again, which is what makes an invoice total reconcile
 * exactly with its lines and each line with its event. `Double` is unusable: `0.1 + 0.2 != 0.3`.
 */
data class Money(val amount: BigDecimal, val currency: Currency) : Comparable<Money> {

    init {
        require(amount.scale() <= SCALE) {
            "amount scale must be at most $SCALE, was ${amount.scale()}"
        }
    }

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        // No rounding: both operands are already at the canonical scale.
        return Money(amount.add(other.amount), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount.subtract(other.amount), currency)
    }

    fun isZero(): Boolean = amount.signum() == 0

    fun isNegative(): Boolean = amount.signum() < 0

    /**
     * Compares by value, ignoring scale: `BigDecimal.equals` treats `2.0` and `2.00` as
     * different, which is almost never what billing code means.
     */
    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amount.compareTo(other.amount)
    }

    override fun toString(): String = "${amount.toPlainString()} ${currency.currencyCode}"

    private fun requireSameCurrency(other: Money) =
        require(currency == other.currency) {
            "Cannot combine ${currency.currencyCode} with ${other.currency.currencyCode}"
        }

    companion object {
        const val SCALE = 4
        val ROUNDING: RoundingMode = RoundingMode.HALF_UP

        fun zero(currency: Currency): Money = Money(BigDecimal.ZERO.setScale(SCALE), currency)

        /** Normalises [amount] to the canonical scale, rounding [HALF_UP]. */
        fun of(amount: BigDecimal, currency: Currency): Money =
            Money(amount.setScale(SCALE, ROUNDING), currency)

        fun of(amount: String, currency: Currency): Money = of(BigDecimal(amount), currency)

        /** The single place a rated amount is produced: exact multiply, rounded once. */
        fun rate(quantity: Quantity, unitPrice: UnitPrice, currency: Currency): Money =
            of(quantity.value.multiply(unitPrice.value), currency)

        /** Sums already-rounded amounts. Never rounds again. */
        fun sum(amounts: List<Money>, currency: Currency): Money =
            amounts.fold(zero(currency)) { acc, money -> acc + money }
    }
}
