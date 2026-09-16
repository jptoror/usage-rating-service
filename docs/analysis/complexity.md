# Complexity and performance

Cost of each operation, where the limits are, and what would need to change first.

Notation: **N** = transactions in a period for one customer · **M** = messages in an
outbox batch · **R** = pricing rules for one `(tenant, code)` · **W** = worker instances.

---

## Summary

Throughput figures below are **measured**, not estimated — see
[performance.md](performance.md) for the method and the hardware.

| Operation | Time | Queries | Allocations | Limit reached at |
| --- | --- | --- | --- | --- |
| Ingest one transaction | O(1) | 2 writes | O(1) | write throughput |
| Duplicate detection | O(log n) | 1 insert (fails) + 1 read | O(1) | never |
| Resolve a pricing rule | O(log R) | 1 indexed read | O(1) | never |
| Rate one message | O(1) | 2 reads + 1 write | O(1) | never |
| Claim a batch | O(M log n) | 1 select + 1 update | O(M) | lock contention |
| **Summarise an open period** | **O(N log N)** | **1 read** | **O(N)** | **~10⁵ transactions** |
| Summarise a closed period | O(L log L) | 2 reads | O(L) | never — L ≈ lines |
| Close a period | O(N log N) | 1 read + 1 + L writes | O(N) | as above |
| Reconciliation report | O(1) | 5 aggregates | O(1) | never |
| Reconciliation lines | O(N) | 1 read | O(N) | paging needed |

Everything is O(1) or O(log n) except the aggregations, which are linear in the data
they aggregate. That is expected; what matters is whether the constant and the memory
are acceptable, which is the subject below.

---

## Ingestion — O(1)

```kotlin
rawEvents.saveAndFlush(entity)                     // 1 insert
outbox.save(OutboxMessage(...))                    // 1 insert
```

Two inserts, one transaction, no reads. Duplicate detection costs nothing extra in the
happy path: the unique index is checked as part of the insert that was happening anyway.

The rejected path adds one read, only after the database has already established that
the row exists:

```kotlin
catch (e: DataIntegrityViolationException) {
    rawEvents.findByTenantIdAndEventId(...)        // O(log n) — only on a duplicate
}
```

**Why this beats a read-then-write.** Checking first would cost a read on *every*
request to avoid a read on the rare duplicate — and would still be wrong, because
between the check and the insert another request can win. The insert-and-catch is both
cheaper in the common case and correct in the uncommon one.

---

## Pricing rule resolution — O(log R)

```sql
WHERE tenant_id = ? AND transaction_code = ?
  AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
```

`ix_pricing_rule_lookup` covers `(tenant_id, transaction_code, effective_from)`, so this
is an index range scan. R is the number of rules for one code — a handful, historically
— and the `EXCLUDE` constraint guarantees at most one match, so there is no scan of
candidates and no tie-break.

**Not cached, deliberately.** A cache would have to be invalidated on rule change and
would be consulted with an `occurredAt` that varies per event, so the hit rate would be
poor while the invalidation bug would be severe: serving a stale price silently produces
wrong money. At O(log R) against a table of tens of rows, the cache would save
microseconds and risk a financial defect.

---

## Claiming a batch — O(M log n), and the contention question

```sql
SELECT ... FROM outbox_message o JOIN raw_event e ON e.id = o.raw_event_id
WHERE o.status IN ('PENDING','UNRATED') AND o.next_attempt_at <= ?
ORDER BY o.id LIMIT ? FOR UPDATE OF o SKIP LOCKED
```

`ix_outbox_claimable` is a **partial** index on `(next_attempt_at, id)` covering only
`PENDING` and `UNRATED`. That matters more than it looks: `DONE` rows accumulate for
ever and are the vast majority, so a full index would grow without bound while the
useful portion stayed small. The partial index stays proportional to the queue, not to
history.

**How this scales with W — measured.** `SKIP LOCKED` means workers never block on each
other, so adding instances adds throughput **until the database becomes the constraint**,
which happens sooner than the claim query's own cost would suggest.

On this hardware, three tuned instances reached 255 events/s against 228 for one — a 12%
gain for 3× the compute. Ingestion throughput fell at the same time (329 → 263 ev/s),
which is the signature of contention on a shared PostgreSQL rather than on the claim.

The claim query itself is not the problem at this scale: `W / pollInterval` is 15
queries/s for three instances at 200 ms. The write path is.

**The real ceiling is the poll interval, not the work.** This was measured rather than
reasoned about, and the reasoning was wrong: the estimate here previously said "a few
hundred messages per second per worker", and a single instance at the default settings
actually sustains **36 events/s**.

The arithmetic is simple once measured: one batch of 50 per poll, one poll per second,
so 50/s is the design ceiling regardless of how fast the database is. The workers were
idle, not saturated.

**The defaults changed as a result** — 200 ms and 200 per batch, raising the ceiling from
50/s to ~1,000/s. A configuration value was the constraint, and no amount of database or
JVM tuning would have found it.

Measured figures and the full analysis are in [performance.md](performance.md).

---

## Invoice summary — O(N log N), and the one real limit

This is the only operation whose cost is worth arguing about.

```kotlin
val rated = ratedTransactions.findCurrentForBillingPeriod(...)   // O(N) rows into memory
rated.groupBy { it.transactionCode to it.originPeriod }          // O(N)
     .map { (_, group) -> group.fold(...) }                      // O(N) total
lines.sortedWith(...)                                            // O(L log L), L ≪ N
```

The query is index-backed and the aggregation is linear, so time is fine. **Memory is
the problem**: N entities are materialised to produce a handful of summary lines. A
customer with 100,000 transactions in a month loads 100,000 Hibernate entities — roughly
50 MB of heap — to compute five numbers.

**Why it is written this way.** The amounts must be **summed**, never recalculated from
quantity × price, or the total stops matching its own lines. That is a correctness
constraint, not a style preference, and it is the reason the naive `SELECT SUM(amount)`
is not automatically safe: the sum must be over already-rounded per-transaction amounts,
in the right grouping.

That constraint is satisfiable in SQL. The aggregation could be:

```sql
SELECT transaction_code, origin_period, is_late_adjustment,
       count(*), sum(quantity), sum(amount)     -- sums the rounded amounts, correctly
FROM rated_transaction
WHERE tenant_id = ? AND customer_id = ? AND billing_period = ? AND superseded_by IS NULL
GROUP BY transaction_code, origin_period, is_late_adjustment
```

O(N) in the database, **O(L) in the application** — bounded by the number of distinct
codes rather than by transaction volume.

**Why it has not been changed.** The in-memory version is the one covered by unit tests
that assert the rounding behaviour directly, without a database. Moving the arithmetic
into SQL moves it out of reach of those tests, and the property being protected —
"the total equals the sum of its lines, exactly" — is the single most important thing
this service does. Making that change without first porting those assertions to an
integration test would trade a known-correct implementation for a faster unknown.

**Recommendation:** switch to the SQL aggregate when a customer's monthly volume
approaches 10⁴ transactions, and port the rounding assertions to `@DataJpaTest` in the
same change. Below that, the in-memory version costs single-digit milliseconds and a few
hundred kilobytes.

---

## Reconciliation report — O(1)

Five `count(*)` and `sum()` aggregates, each index-backed, returning scalars:

```sql
SELECT count(*) FROM raw_event WHERE tenant_id = ? AND customer_id = ? AND occurred_at >= ? AND ...
SELECT coalesce(sum(duplicate_delivery_count), 0) FROM raw_event WHERE ...
SELECT o.status, count(*) FROM outbox_message o JOIN raw_event e ... GROUP BY o.status
```

Nothing is materialised in the application regardless of volume. The database scans an
index range proportional to the period's data, but returns constant output.

**The duplicate count is a sum, not a row count.** Each re-delivery increments a counter
on the original event rather than inserting a row, so a retry storm that repeats one
event 10,000 times costs 10,000 cheap updates rather than 10,000 rows that would then
have to be counted and stored for ever.

---

## Reconciliation lines — O(N), and the missing page

```kotlin
fun lines(customer, period, transactionCode): List<ReconciliationLine>
```

Returns every matching row with no `LIMIT`. For the intended use — tracing a summary
line of a few hundred transactions — that is right: a reviewer wants the whole
derivation, not a page of it.

For a customer with 100,000 transactions in a period, this serialises 100,000 JSON
objects into one response. **This is a real gap**, listed here rather than hidden: the
endpoint should take `limit` and `cursor` before it meets that kind of volume. It is not
implemented because paging a traceability endpoint well means deciding what a stable
cursor is across corrections, and that deserves more thought than a `LIMIT` clause.

---

## Monetary arithmetic

`BigDecimal` is roughly 50–100× slower than `double` for multiplication. It is also the
only correct choice, so the comparison is not a trade-off — but the cost is worth
placing precisely:

- One multiplication and one rounding per rated transaction: **~100 ns**.
- At 1,000 transactions/second: **0.01%** of one core.

The database round trip for the same transaction is ~1 ms — four orders of magnitude
larger. Monetary arithmetic is not measurable in this system's profile.

---

## What would break first

In order:

1. **Invoice summary memory**, at ~10⁴ transactions per customer per month. Fix: the
   SQL aggregate above.
2. **Reconciliation lines response size**, same threshold. Fix: paging.
3. **Outbox table growth.** `DONE` rows are never removed. The partial index keeps
   queries fast, but the table grows without bound. Fix: archive `DONE` rows older than
   the retention window. Not urgent — the rows are small — but it is unbounded, which
   eventually matters.
4. **The shared database**, at roughly **250 events/s** on this hardware — reached by a
   single tuned instance, with three instances adding only 12%. The application is no
   longer the constraint at that point. Fix, in order: tune the poll interval and batch
   size (free, 6.3× measured), then database capacity, and only then more instances or a
   broker. The original estimate of "a few hundred per instance" was wrong twice over:
   the ceiling was configuration, and then it was the database.

None of these is reached by the volumes this exercise describes. They are written down
because "it scales" is not a claim worth making without naming the point where it stops.
