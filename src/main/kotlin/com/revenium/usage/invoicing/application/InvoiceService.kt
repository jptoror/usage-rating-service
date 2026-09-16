package com.revenium.usage.invoicing.application

import com.revenium.usage.invoicing.domain.Invoice
import com.revenium.usage.invoicing.domain.InvoiceLine
import com.revenium.usage.invoicing.domain.InvoiceStatus
import com.revenium.usage.invoicing.domain.InvoiceSummary
import com.revenium.usage.invoicing.domain.SummaryLine
import com.revenium.usage.invoicing.infrastructure.InvoiceJpaRepository
import com.revenium.usage.invoicing.infrastructure.InvoiceLineJpaRepository
import com.revenium.usage.invoicing.domain.Charge
import com.revenium.usage.invoicing.domain.ChargeLookup
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.tenancy.RequiresTenant
import com.revenium.usage.tenancy.TenantContext
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.Currency

private val log = KotlinLogging.logger {}

/**
 * Produces invoice summaries and closes billing periods.
 *
 * ### On-read while open, materialised on close
 *
 * An open period is aggregated from `rated_transaction` every time it is asked for, so
 * the summary always reflects the latest rating with no cache to invalidate and no
 * recomputation step to schedule. Closing freezes the figures into `invoice` and
 * `invoice_line`, and from then on the closed invoice is read back verbatim.
 *
 * That split is what makes the late-arrival policy work: nothing recomputes a closed
 * period, so a customer never sees a figure change after the fact.
 */
@Service
@RequiresTenant
class InvoiceService(
    private val charges: ChargeLookup,
    private val invoices: InvoiceJpaRepository,
    private val invoiceLines: InvoiceLineJpaRepository,
    private val defaultCurrency: Currency,
    private val clock: Clock,
) {

    /**
     * The summary for a customer and period.
     *
     * `readOnly` so the driver can skip dirty checking and the database knows no writes
     * are coming — meaningful on a query that may scan a month of transactions.
     */
    @Transactional(readOnly = true)
    fun summarise(customer: CustomerId, period: BillingPeriod): InvoiceSummary {
        val tenant = TenantContext.current()
        val existing = invoices.findByTenantIdAndCustomerIdAndPeriodStart(
            tenant.value, customer.value, period.startDate,
        )

        return if (existing?.status == InvoiceStatus.CLOSED) {
            // A closed invoice is read back exactly as it was frozen. Re-aggregating
            // would risk reporting a different figure than the one already billed.
            summariseFromClosedInvoice(existing, customer, period)
        } else {
            summariseFromRatedTransactions(customer, period, existing)
        }
    }

    private fun summariseFromClosedInvoice(
        invoice: Invoice,
        customer: CustomerId,
        period: BillingPeriod,
    ): InvoiceSummary {
        val currency = Currency.getInstance(invoice.currency)
        val lines = invoiceLines.findByInvoiceIdOrderByOriginPeriodAscTransactionCodeAsc(invoice.id)
            .map { line ->
                SummaryLine(
                    transactionCode = line.transactionCode,
                    transactionCount = line.transactionCount,
                    totalQuantity = line.totalQuantity,
                    amount = Money(line.amount, currency),
                    originPeriod = BillingPeriod.of(line.originPeriod),
                    isAdjustment = line.isAdjustment,
                )
            }

        return InvoiceSummary(
            customerId = customer,
            period = period,
            currency = currency,
            lines = lines,
            currentPeriodAmount = Money(invoice.currentPeriodAmount, currency),
            adjustmentAmount = Money(invoice.adjustmentAmount, currency),
            totalAmount = Money(invoice.totalAmount, currency),
            transactionCount = invoice.transactionCount,
            status = InvoiceStatus.CLOSED,
        )
    }

    private fun summariseFromRatedTransactions(
        customer: CustomerId,
        period: BillingPeriod,
        existing: Invoice?,
    ): InvoiceSummary {
        val tenant = TenantContext.current()
        val rated = charges.findChargesFor(tenant, customer, period)

        val currency = rated.firstOrNull()?.let { Currency.getInstance(it.currency) } ?: defaultCurrency

        return InvoiceSummary.from(
            customerId = customer,
            period = period,
            currency = currency,
            lines = aggregate(rated, currency),
            status = existing?.status ?: InvoiceStatus.OPEN,
        )
    }

    /**
     * Groups rated transactions into summary lines.
     *
     * Amounts are **summed**, never recalculated from quantity and price: each was
     * rounded once when its transaction was rated, and recalculating would round the
     * aggregate differently, producing a total that no longer matches its own lines.
     */
    private fun aggregate(rated: List<Charge>, currency: Currency): List<SummaryLine> =
        rated.groupBy { it.transactionCode to it.originPeriod }
            .map { (key, group) ->
                val (code, originPeriod) = key
                SummaryLine(
                    transactionCode = code,
                    transactionCount = group.size.toLong(),
                    totalQuantity = group.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.quantity) },
                    amount = Money.sum(group.map { Money(it.amount, currency) }, currency),
                    originPeriod = BillingPeriod.of(originPeriod),
                    isAdjustment = group.first().isLateAdjustment,
                )
            }

    /**
     * Closes a period, freezing its totals.
     *
     * ### Isolation
     *
     * `REPEATABLE_READ`, not the default `READ_COMMITTED`. Closing reads every rated
     * transaction in the period and writes the aggregate; under `READ_COMMITTED` a
     * transaction rated concurrently could appear midway through, so the header and the
     * lines would disagree. `REPEATABLE_READ` gives one consistent snapshot. PostgreSQL
     * may raise a serialisation error under contention, and the correct response is to
     * retry the close — not to weaken the isolation.
     *
     * Closing twice is refused: the unique constraint on `(tenant, customer, period)`
     * makes a duplicate impossible, and [Invoice.close] refuses to overwrite figures a
     * customer may already have been billed from.
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.REPEATABLE_READ)
    fun closePeriod(customer: CustomerId, period: BillingPeriod): InvoiceSummary {
        val tenant = TenantContext.current()

        invoices.findByTenantIdAndCustomerIdAndPeriodStart(
            tenant.value, customer.value, period.startDate,
        )?.let { existing ->
            check(!existing.isClosed) {
                "Period $period for customer $customer is already closed"
            }
        }

        val rated = charges.findChargesFor(tenant, customer, period)
        val currency = rated.firstOrNull()?.let { Currency.getInstance(it.currency) } ?: defaultCurrency
        val lines = aggregate(rated, currency)
        val summary = InvoiceSummary.from(customer, period, currency, lines, InvoiceStatus.CLOSED)

        val invoice = invoices.save(
            Invoice(
                tenantId = tenant.value,
                customerId = customer.value,
                periodStart = period.startDate,
                periodEnd = period.endDate,
                currency = currency.currencyCode,
            ).apply {
                close(
                    currentPeriod = summary.currentPeriodAmount.amount,
                    adjustments = summary.adjustmentAmount.amount,
                    transactions = summary.transactionCount,
                    now = clock.instant(),
                )
            }
        )

        invoiceLines.saveAll(
            summary.lines.map { line ->
                InvoiceLine(
                    tenantId = tenant.value,
                    invoiceId = invoice.id,
                    transactionCode = line.transactionCode,
                    transactionCount = line.transactionCount,
                    totalQuantity = line.totalQuantity,
                    amount = line.amount.amount,
                    originPeriod = line.originPeriod.startDate,
                    isAdjustment = line.isAdjustment,
                )
            }
        )

        log.info {
            "Closed $period for customer $customer: ${summary.totalAmount} " +
                "across ${summary.transactionCount} transactions"
        }
        return summary
    }
}
