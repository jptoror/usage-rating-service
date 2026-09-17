---
name: financial-correctness
description: Rules for monetary arithmetic, rounding, effective-dated pricing lookups and billing-period boundaries in this service. Use whenever writing or changing code that computes an amount, resolves a pricing rule, determines a billing period, or defines a database column that holds money.
---

# Money, rounding and effective dating

Financial correctness is the single thing this service is graded on hardest. The
rules below are not style preferences.

## Types

| Concept | Kotlin | Postgres | Scale |
| --- | --- | --- | --- |
| Unit price | `BigDecimal` | `NUMERIC(19,6)` | 6 |
| Quantity | `BigDecimal` | `NUMERIC(19,6)` | 6 |
| Amount | `BigDecimal` | `NUMERIC(19,4)` | 4 |
| Currency | `Currency` / `String` | `CHAR(3)` | ISO 4217 |

`Double` and `Float` are banned outright in this path. `0.1 + 0.2 != 0.3` in binary
floating point, and an invoice that is off by 0.00000001 is an invoice that a
reviewer will find.

## Arithmetic rules

**Always specify scale and rounding on division.** `BigDecimal.divide(other)` with no
rounding throws `ArithmeticException` when the result does not terminate — `10 / 3`
is enough to trigger it.

```kotlin
// Wrong -- throws on non-terminating results
val rate = amount.divide(quantity)

// Right
val rate = amount.divide(quantity, 6, RoundingMode.HALF_UP)
```

**Compare with `compareTo`, never `==`.** `BigDecimal("2.0") == BigDecimal("2.00")`
is `false`, because `equals` compares scale too. In Kotlin, `==` on `BigDecimal` calls
`equals`:

```kotlin
// Wrong
if (amount == BigDecimal.ZERO) ...

// Right
if (amount.compareTo(BigDecimal.ZERO) == 0) ...
if (amount.signum() == 0) ...                 // clearer for zero checks
```

**Round exactly once, at the end, per transaction:**

```kotlin
fun rate(quantity: BigDecimal, unitPrice: BigDecimal): BigDecimal =
    quantity.multiply(unitPrice)                    // full precision here
        .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)  // and round once, here
```

Never round an intermediate result. Never round the invoice total — the total is the
sum of already-rounded line amounts, so that every line reconciles to its event and
the total reconciles to the lines. This property is what makes the reconciliation
report meaningful, and a reviewer will check it.

## Effective-dated rule resolution

A rule is valid over `[effective_from, effective_to)` — **start inclusive, end
exclusive**. The half-open interval removes the ambiguity at the changeover instant,
which is where off-by-one-cent bugs live.

```kotlin
fun PricingRule.appliesAt(instant: Instant): Boolean =
    !instant.isBefore(effectiveFrom) &&               // from <= instant
    (effectiveTo == null || instant.isBefore(effectiveTo))   // instant < to
```

Two rules must never overlap for the same `(tenant, transactionCode)`. This is
enforced by a Postgres `EXCLUDE USING gist` constraint, not by application logic —
application-level checks race under concurrent rule creation.

**Resolve by `occurredAt`, never by "now".** An August event reprocessed in October
is rated at August's price. Getting this wrong makes every replay produce a different
answer than the original, which destroys reconciliation.

**No applicable rule is not an error.** The event becomes `UNRATED` and is retried:
the rule may legitimately be created later. Never bill it at zero, never reject it.

## Billing periods

- The period is the calendar month in UTC, `[first day 00:00:00Z, next month 00:00:00Z)`.
- `occurredAt` determines the period. `receivedAt` determines lateness. Both persist.
- Period boundaries are half-open, exactly like rule validity, for the same reason.

```kotlin
fun billingPeriodOf(occurredAt: Instant): YearMonth =
    YearMonth.from(occurredAt.atZone(ZoneOffset.UTC))
```

An event at `2026-08-31T23:59:59.999Z` belongs to August. An event at
`2026-09-01T00:00:00.000Z` belongs to September. Both deserve a test.

## Boundary cases that must have tests

Every one of these is a real failure mode, not a hypothetical:

| Case | Expected |
| --- | --- |
| `occurredAt` exactly `effective_from` | rule applies |
| `occurredAt` exactly `effective_to` | rule does **not** apply |
| `effective_to` is null | rule applies indefinitely |
| no rule covers `occurredAt` | `UNRATED`, retried, not zero and not rejected |
| `0.005` rounded at scale 2 | `0.01` under `HALF_UP` |
| quantity is fractional | full precision retained until the final rounding |
| amount near `NUMERIC(19,4)` limits | no overflow, no silent truncation |
| quantity zero or negative | rejected at validation |
| last instant of a month | belongs to that month |
| first instant of a month | belongs to the new month |

## Persisting

- Read money back as `BigDecimal`, never through a `Double` accessor.
- `columnDefinition` or an explicit `precision`/`scale` on the JPA column; do not let
  Hibernate infer it.
- Assert the scale after loading if a calculation depends on it — a `NUMERIC(19,4)`
  round-trips as scale 4, and code that assumes scale 2 will produce surprises.
