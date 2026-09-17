package com.revenium.usage.invoicing.application

import com.revenium.usage.invoicing.domain.model.Charge
import com.revenium.usage.invoicing.domain.model.Invoice
import com.revenium.usage.invoicing.domain.model.InvoiceLine
import com.revenium.usage.invoicing.domain.model.InvoiceStatus
import com.revenium.usage.invoicing.domain.model.InvoiceSummary
import com.revenium.usage.invoicing.domain.model.SummaryLine
import com.revenium.usage.invoicing.domain.model.UsageLine
import com.revenium.usage.invoicing.domain.model.UsageSummary
import com.revenium.usage.invoicing.domain.port.`in`.ClosePeriodUseCase
import com.revenium.usage.invoicing.domain.port.`in`.SummariseInvoiceUseCase
import com.revenium.usage.invoicing.domain.port.out.ChargeLookup
import com.revenium.usage.invoicing.domain.port.out.InvoiceStore
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.shared.domain.Quantity
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
 * An open period is aggregated from the rated charges on every read — no cache to invalidate —
 * while closing freezes the figures into the invoice and its lines, read back verbatim
 * thereafter. That split is what makes the late-arrival policy work: nothing recomputes a
 * closed period, so a customer never sees a figure change after the fact.
 */
@Service
@RequiresTenant
class InvoiceService(
    private val charges: ChargeLookup,
    private val invoices: InvoiceStore,
    private val defaultCurrency: Currency,
    private val clock: Clock,
) : SummariseInvoiceUseCase, ClosePeriodUseCase {

    /** `readOnly`: no dirty checking, on a query that may scan a month of transactions. */
    @Transactional(readOnly = true)
    override fun summarise(customer: CustomerId, period: BillingPeriod): InvoiceSummary {
        val tenant = TenantContext.current()
        val existing = invoices.findInvoice(tenant, customer, period)

        return if (existing?.status == InvoiceStatus.CLOSED) {
            // Read back exactly as frozen: re-aggregating could report a different figure
            // than the one already billed.
            summariseFromClosedInvoice(existing, customer, period)
        } else {
            summariseFromRatedTransactions(customer, period, existing)
        }
    }

    /**
     * Totals usage across a span of periods.
     *
     * Built by summarising each period in turn rather than with one wide query, so a
     * closed period still reports exactly the figures it was billed at. A single range
     * query over `rated_transaction` would silently re-aggregate closed months and could
     * disagree with an invoice already sent.
     *
     * The cost is one query per period in the range, which is why the range is bounded.
     */
    @Transactional(readOnly = true)
    override fun summariseRange(
        customer: CustomerId,
        from: BillingPeriod,
        to: BillingPeriod,
    ): UsageSummary {
        require(from <= to) { "from ($from) must not be after to ($to)" }
        val span = generateSequence(from) { it.next() }.takeWhile { it <= to }.toList()
        require(span.size <= MAX_RANGE_MONTHS) {
            "A range may cover at most $MAX_RANGE_MONTHS months, was ${span.size}"
        }

        val summaries = span.map { it to summarise(customer, it) }

        // Lines carry an originPeriod within a single invoice; across a range that
        // distinction belongs to the period breakdown, so codes are totalled here.
        val lines = summaries
            .flatMap { (_, summary) -> summary.lines }
            .groupBy { it.transactionCode }
            .map { (code, group) ->
                UsageLine(
                    transactionCode = code,
                    transactionCount = group.sumOf { it.transactionCount },
                    totalQuantity = Quantity(group.sumOf { it.totalQuantity.value }),
                    // Summed, never recalculated: each amount was rounded once at rating.
                    amount = Money.sum(group.map { it.amount }, group.first().amount.currency),
                )
            }

        val currency = summaries.firstOrNull { it.second.lines.isNotEmpty() }
            ?.second?.currency
            ?: defaultCurrency

        return UsageSummary.from(
            customer = customer,
            from = from,
            to = to,
            currency = currency,
            periods = summaries.map { (period, summary) ->
                UsageSummary.PeriodStatus(period, summary.status)
            },
            lines = lines,
        )
    }

    private fun summariseFromClosedInvoice(
        invoice: Invoice,
        customer: CustomerId,
        period: BillingPeriod,
    ): InvoiceSummary {
        val lines = invoices.findLines(invoice).map { line ->
            SummaryLine(
                transactionCode = line.transactionCode,
                transactionCount = line.transactionCount,
                totalQuantity = line.totalQuantity,
                amount = line.amount,
                originPeriod = line.originPeriod,
                isAdjustment = line.isAdjustment,
            )
        }

        return InvoiceSummary(
            customerId = customer,
            period = period,
            currency = invoice.currency,
            lines = lines,
            currentPeriodAmount = invoice.currentPeriodAmount,
            adjustmentAmount = invoice.adjustmentAmount,
            totalAmount = invoice.totalAmount,
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

        val currency = rated.firstOrNull()?.amount?.currency ?: defaultCurrency

        return InvoiceSummary.from(
            customerId = customer,
            period = period,
            currency = currency,
            lines = aggregate(rated, currency),
            status = existing?.status ?: InvoiceStatus.OPEN,
        )
    }

    /**
     * Groups rated charges into summary lines. Amounts are summed, never recalculated from
     * quantity and price: each was rounded once at rating time, and rounding the aggregate
     * again would produce a total that no longer matches its own lines.
     */
    private fun aggregate(rated: List<Charge>, currency: Currency): List<SummaryLine> =
        rated.groupBy { it.transactionCode to it.originPeriod }
            .map { (key, group) ->
                val (code, originPeriod) = key
                SummaryLine(
                    transactionCode = code,
                    transactionCount = group.size.toLong(),
                    totalQuantity = Quantity(
                        group.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.quantity.value) }
                    ),
                    amount = Money.sum(group.map { it.amount }, currency),
                    originPeriod = originPeriod,
                    isAdjustment = group.first().isLateAdjustment,
                )
            }

    /**
     * Closes a period, freezing its totals.
     *
     * `REPEATABLE_READ` rather than the default: under `READ_COMMITTED` a concurrently rated
     * transaction could appear midway through the aggregation and the header would disagree
     * with the lines. A serialisation error under contention means retry the close, never
     * weaken the isolation.
     *
     * Closing twice is refused, by the unique constraint on `(tenant, customer, period)` and
     * by [Invoice.close], which will not overwrite figures a customer may have been billed from.
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.REPEATABLE_READ)
    override fun closePeriod(customer: CustomerId, period: BillingPeriod): InvoiceSummary {
        val tenant = TenantContext.current()

        invoices.findInvoice(tenant, customer, period)?.let { existing ->
            check(!existing.isClosed) {
                "Period $period for customer $customer is already closed"
            }
        }

        val rated = charges.findChargesFor(tenant, customer, period)
        val currency = rated.firstOrNull()?.amount?.currency ?: defaultCurrency
        val lines = aggregate(rated, currency)
        val summary = InvoiceSummary.from(customer, period, currency, lines, InvoiceStatus.CLOSED)

        val invoice = invoices.saveInvoice(
            Invoice(
                tenantId = tenant,
                customerId = customer,
                period = period,
                currency = currency,
            ).close(
                currentPeriod = summary.currentPeriodAmount,
                adjustments = summary.adjustmentAmount,
                transactions = summary.transactionCount,
                now = clock.instant(),
            )
        )

        invoices.saveLines(
            summary.lines.map { line ->
                InvoiceLine(
                    tenantId = tenant,
                    invoiceId = invoice.id,
                    transactionCode = line.transactionCode,
                    transactionCount = line.transactionCount,
                    totalQuantity = line.totalQuantity,
                    amount = line.amount,
                    originPeriod = line.originPeriod,
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

    private companion object {
        /**
         * Bound on a range summary, in months.
         *
         * The range is built one period at a time to keep closed periods frozen, so its
         * cost is linear in the span. Two years is past any reporting question this
         * service is meant to answer directly; anything wider is a data export.
         */
        const val MAX_RANGE_MONTHS = 24
    }
}
