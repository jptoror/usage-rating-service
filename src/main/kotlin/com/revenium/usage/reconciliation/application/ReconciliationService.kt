package com.revenium.usage.reconciliation.application

import com.revenium.usage.reconciliation.domain.EventState
import com.revenium.usage.reconciliation.domain.ReconciliationLine
import com.revenium.usage.reconciliation.domain.ReconciliationReport
import com.revenium.usage.reconciliation.domain.StateCount
import com.revenium.usage.reconciliation.domain.port.`in`.ReconcileUseCase
import com.revenium.usage.shared.domain.BillingPeriod
import com.revenium.usage.shared.domain.CustomerId
import com.revenium.usage.shared.domain.Money
import com.revenium.usage.tenancy.RequiresTenant
import com.revenium.usage.tenancy.TenantContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.util.Currency

/**
 * Produces reconciliation evidence: where every received event ended up, and how any
 * billed total traces back to the events and pricing rules behind it.
 *
 * JDBC rather than JPA: these are aggregate projections over never-mutated data, and a mapping
 * layer would obscure the arithmetic the report exists to make checkable. Every query still
 * filters by tenant explicitly even though row-level security would do it anyway — the
 * application states its intent and the database enforces it independently.
 */
@Service
@RequiresTenant
class ReconciliationService(
    private val jdbc: JdbcTemplate,
    private val defaultCurrency: Currency,
    private val clock: Clock,
) : ReconcileUseCase {

    /**
     * Counts of every state for a customer and period, plus the amount billed.
     *
     * Reports by `origin_period`, not `billing_period`: the question is whether what we
     * received in a period accounts for what we billed for it, so both sides must be measured
     * by `occurred_at`. Using `billing_period` was a real defect — a late adjustment counted
     * on the received side of its origin period and the billed side of a later one, so
     * neither period could ever balance. `billing_period` belongs to the invoice summary,
     * which answers the different question of what a customer owes this period.
     */
    @Transactional(readOnly = true)
    override fun report(customer: CustomerId, period: BillingPeriod): ReconciliationReport {
        val tenant = TenantContext.current().value
        val from = Timestamp.from(period.start)
        val to = Timestamp.from(period.end)

        val received = jdbc.queryForObject(
            """
            SELECT count(*) FROM raw_event
            WHERE tenant_id = ? AND customer_id = ? AND occurred_at >= ? AND occurred_at < ?
            """,
            Long::class.java, tenant, customer.value, from, to,
        ) ?: 0

        val rejected = jdbc.queryForObject(
            """
            SELECT count(*) FROM rejected_event
            WHERE tenant_id = ? AND customer_id = ? AND occurred_at >= ? AND occurred_at < ?
            """,
            Long::class.java, tenant, customer.value, from, to,
        ) ?: 0

        // A duplicate creates no raw_event row of its own, so it is counted from the tally on
        // the event it duplicated: summing reports a retry storm accurately, not as one duplicate.
        val duplicates = jdbc.queryForObject(
            """
            SELECT coalesce(sum(duplicate_delivery_count), 0) FROM raw_event
            WHERE tenant_id = ? AND customer_id = ? AND occurred_at >= ? AND occurred_at < ?
            """,
            Long::class.java, tenant, customer.value, from, to,
        ) ?: 0

        val outboxCounts = jdbc.query(
            """
            SELECT o.status, count(*) AS n
            FROM outbox_message o
            JOIN raw_event e ON e.id = o.raw_event_id
            WHERE o.tenant_id = ? AND e.customer_id = ? AND e.occurred_at >= ? AND e.occurred_at < ?
            GROUP BY o.status
            """,
            { rs, _ -> rs.getString("status") to rs.getLong("n") },
            tenant, customer.value, from, to,
        ).toMap()

        // RATED while the period is open, INVOICED once the invoice is frozen.
        val ratedRows = jdbc.query(
            """
            SELECT coalesce(i.status, 'OPEN') AS invoice_status,
                   count(*) AS n,
                   coalesce(sum(r.amount), 0) AS total,
                   min(r.currency) AS currency
            FROM rated_transaction r
            -- Joined on billing_period: whether a charge is INVOICED depends on the invoice
            -- it landed in, even while it is counted under its origin.
            LEFT JOIN invoice i
                   ON i.tenant_id = r.tenant_id
                  AND i.customer_id = r.customer_id
                  AND i.period_start = r.billing_period
            WHERE r.tenant_id = ? AND r.customer_id = ? AND r.origin_period = ?
              AND r.superseded_by IS NULL
            GROUP BY coalesce(i.status, 'OPEN')
            """,
            { rs, _ ->
                Triple(
                    rs.getString("invoice_status"),
                    rs.getLong("n"),
                    rs.getBigDecimal("total") to rs.getString("currency"),
                )
            },
            tenant, customer.value, period.startDate,
        )

        val currency = ratedRows.firstNotNullOfOrNull { it.third.second }
            ?.let(Currency::getInstance) ?: defaultCurrency

        val ratedOpen = ratedRows.firstOrNull { it.first == "OPEN" }
        val ratedClosed = ratedRows.firstOrNull { it.first == "CLOSED" }

        val states = listOf(
            StateCount(EventState.REJECTED, rejected, null),
            StateCount(EventState.DUPLICATE, duplicates, null),
            StateCount(EventState.ACCEPTED, outboxCounts["PENDING"] ?: 0, null),
            StateCount(EventState.UNRATED, outboxCounts["UNRATED"] ?: 0, null),
            StateCount(EventState.FAILED, outboxCounts["FAILED"] ?: 0, null),
            StateCount(EventState.QUARANTINED, outboxCounts["QUARANTINED"] ?: 0, null),
            StateCount(
                EventState.RATED,
                ratedOpen?.second ?: 0,
                Money.of(ratedOpen?.third?.first ?: BigDecimal.ZERO, currency),
            ),
            StateCount(
                EventState.INVOICED,
                ratedClosed?.second ?: 0,
                Money.of(ratedClosed?.third?.first ?: BigDecimal.ZERO, currency),
            ),
        )

        val billed = Money.of(
            (ratedOpen?.third?.first ?: BigDecimal.ZERO).add(ratedClosed?.third?.first ?: BigDecimal.ZERO),
            currency,
        )

        return ReconciliationReport(
            customerId = customer,
            period = period,
            // Everything that arrived, rejections and duplicate deliveries included.
            receivedCount = received + rejected + duplicates,
            states = states,
            billedAmount = billed,
            generatedAt = clock.instant(),
        )
    }

    /**
     * Every rated transaction behind a period's total, traced to its event and rule.
     *
     * Matched on `billing_period`, unlike [report]: this traces an invoice figure, and an
     * invoice contains exactly what was charged in its period, adjustments included.
     */
    @Transactional(readOnly = true)
    override fun lines(
        customer: CustomerId,
        period: BillingPeriod,
        // No default value: on a proxied method Kotlin emits a synthetic
        // DefaultConstructorMarker parameter that Spring then tries to autowire.
        transactionCode: String?,
    ): List<ReconciliationLine> {
        val tenant = TenantContext.current().value

        return jdbc.query(
            """
            SELECT e.event_id, e.id AS raw_event_id, e.received_at,
                   r.transaction_code, r.occurred_at, r.quantity, r.unit_price,
                   r.amount, r.currency, r.pricing_rule_id,
                   r.origin_period, r.billing_period, r.is_late_adjustment,
                   coalesce(i.status, 'OPEN') AS invoice_status
            FROM rated_transaction r
            JOIN raw_event e ON e.id = r.raw_event_id
            LEFT JOIN invoice i
                   ON i.tenant_id = r.tenant_id
                  AND i.customer_id = r.customer_id
                  AND i.period_start = r.billing_period
            WHERE r.tenant_id = ? AND r.customer_id = ? AND r.billing_period = ?
              AND r.superseded_by IS NULL
              AND (CAST(? AS TEXT) IS NULL OR r.transaction_code = ?)
            ORDER BY r.transaction_code, r.occurred_at
            """,
            { rs, _ ->
                val currency = Currency.getInstance(rs.getString("currency"))
                ReconciliationLine(
                    eventId = rs.getString("event_id"),
                    rawEventId = rs.getLong("raw_event_id"),
                    transactionCode = rs.getString("transaction_code"),
                    occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                    receivedAt = rs.getTimestamp("received_at").toInstant(),
                    quantity = rs.getBigDecimal("quantity"),
                    unitPrice = rs.getBigDecimal("unit_price"),
                    amount = Money(rs.getBigDecimal("amount"), currency),
                    pricingRuleId = rs.getLong("pricing_rule_id"),
                    originPeriod = BillingPeriod.of(rs.getDate("origin_period").toLocalDate()),
                    billingPeriod = BillingPeriod.of(rs.getDate("billing_period").toLocalDate()),
                    isLateAdjustment = rs.getBoolean("is_late_adjustment"),
                    state = if (rs.getString("invoice_status") == "CLOSED") {
                        EventState.INVOICED
                    } else {
                        EventState.RATED
                    },
                )
            },
            tenant, customer.value, period.startDate, transactionCode, transactionCode,
        )
    }
}
