# Usage Rating and Invoice Reconciliation Service

Ingests customer usage transactions, rates them under effective-dated pricing rules,
produces invoice summaries, and exposes reconciliation evidence linking every billed
amount back to the events and pricing rules behind it.

[![Quality gates](https://github.com/jptoror/usage-rating-service/actions/workflows/quality-gates.yml/badge.svg)](https://github.com/jptoror/usage-rating-service/actions/workflows/quality-gates.yml)

Kotlin 2.4 · Spring Boot 4.1 · PostgreSQL 16 · Liquibase · Gradle (Kotlin DSL)

---

## Quick start

```bash
cp .env.example .env            # adjust credentials
docker compose up --build       # Postgres + app, migrations applied on startup
```

| | |
| --- | --- |
| Health | http://localhost:8080/actuator/health |
| OpenAPI | http://localhost:8080/v3/api-docs |
| Swagger UI | http://localhost:8080/swagger-ui.html |

### Build and test

```bash
./gradlew check                 # compile + unit tests + integration tests + coverage gate
./gradlew test                  # unit tests only (fast, no Docker)
./gradlew integrationTest       # integration tests only (requires Docker)
./gradlew jacocoTestReport      # -> build/reports/jacoco/test/html/index.html
```

`./gradlew check` is the single command that builds and verifies everything.

Java 21 is required: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`

### Current state

| | |
| --- | --- |
| Unit tests | 309 |
| Integration tests | 44 (Testcontainers) |
| Line coverage | **91.64%** against an 85% gate |
| Scripted checks | 22 end-to-end + 22 edge cases + 8 multi-instance |
| Measured throughput | ~330 events/s ingested, 228 events/s rated on **one** tuned instance |

### Scripts

```bash
./scripts/start.sh --scale 3        # three instances behind nginx
./scripts/e2e-test.sh --evidence    # every functional requirement
./scripts/edge-case-test.sh         # boundaries through the full stack
./scripts/multi-instance-test.sh    # coordination across separate processes
./scripts/load-test.sh --events 3000 --concurrency 50   # throughput and latency
./scripts/stop.sh --clean
```

Each asserts and exits non-zero on failure. `--evidence` writes a timestamped
transcript to [`docs/evidence/`](docs/evidence/).

### Documentation

[`docs/`](docs/README.md) holds the architecture and sequence diagrams, a manual
testing guide, complexity and performance analyses, the evidence transcripts, and
[what CI gates on](docs/CI.md).

Every pull request runs the full set of gates: compile, unit tests with the 85% coverage
gate, integration tests, architecture rules, a Docker end-to-end run, and multi-instance
coordination under contention.

---

## Worked example

Every command below runs against `docker compose up`.

### 1 — Ingest a transaction

```bash
curl -s -X POST localhost:8080/api/v1/transactions \
  -H 'X-Tenant-Id: tenant-a' -H 'Content-Type: application/json' -d '{
    "eventId": "73d4e120-77d0-4f11-a6d2-f3b43b430d9c",
    "tenantId": "tenant-a",
    "customerId": "customer-42",
    "transactionCode": "VEHICLE_REGISTRATION",
    "occurredAt": "2026-08-15T14:22:31Z",
    "metadata": {"source": "upstream-api", "quantity": 2, "batchId": "batch-2026-08-15-01"}
  }'
```

```json
{ "eventId": "73d4e120-…", "receivedAt": "2026-09-16T10:00:00Z", "status": "ACCEPTED" }
```

### 2 — Re-deliver the same event

Running the identical command again returns `200`, not `409`:

```json
{ "eventId": "73d4e120-…", "originalReceivedAt": "2026-09-16T10:00:00Z", "status": "DUPLICATE" }
```

### 3 — Summarise the period

```bash
curl -s -G localhost:8080/api/v1/invoices/summary \
  -H 'X-Tenant-Id: tenant-a' \
  --data-urlencode 'customerId=customer-42' \
  --data-urlencode 'period=2026-08'
```

```json
{
  "period": "2026-08",
  "currency": "USD",
  "status": "OPEN",
  "lines": [
    { "transactionCode": "VEHICLE_REGISTRATION", "transactionCount": 1,
      "amount": 5.0000, "originPeriod": "2026-08", "isAdjustment": false }
  ],
  "currentPeriodAmount": 5.0000,
  "adjustmentAmount": 0.0000,
  "totalAmount": 5.0000
}
```

### 3b — Total a range of periods

```bash
curl -s -G localhost:8080/api/v1/invoices/usage \
  -H 'X-Tenant-Id: tenant-a' \
  --data-urlencode 'customerId=customer-42' \
  --data-urlencode 'from=2026-06' --data-urlencode 'to=2026-08'
```

```json
{
  "from": "2026-06", "to": "2026-08", "currency": "USD",
  "periods": [
    { "period": "2026-06", "status": "CLOSED" },
    { "period": "2026-07", "status": "CLOSED" },
    { "period": "2026-08", "status": "OPEN" }
  ],
  "lines": [
    { "transactionCode": "VEHICLE_REGISTRATION", "transactionCount": 12,
      "totalQuantity": 24, "amount": 60.0000 }
  ],
  "totalAmount": 60.0000,
  "transactionCount": 12
}
```

Closed periods contribute the figures they were billed at, never a re-aggregation, so
this can never disagree with an invoice already sent.

### 4 — Trace the total back to its events

```bash
curl -s -G localhost:8080/api/v1/reconciliation/lines \
  -H 'X-Tenant-Id: tenant-a' \
  --data-urlencode 'customerId=customer-42' \
  --data-urlencode 'period=2026-08'
```

```json
[{ "eventId": "73d4e120-…", "quantity": 2, "unitPrice": 2.500000,
   "amount": 5.0000, "pricingRuleId": 2, "state": "RATED" }]
```

`2 × 2.500000 = 5.0000`, and `GET /api/v1/pricing-rules/2` shows the rule that supplied
the price. Every amount in the system decomposes this way.

### 5 — Check that what arrived accounts for what was billed

```bash
curl -s -G localhost:8080/api/v1/reconciliation/report \
  -H 'X-Tenant-Id: tenant-a' \
  --data-urlencode 'customerId=customer-42' \
  --data-urlencode 'period=2026-08'
```

```json
{ "receivedCount": 2, "balanced": true, "imbalance": null,
  "states": [ { "state": "RATED", "count": 1, "amount": 5.0000 },
              { "state": "DUPLICATE", "count": 1 } ] }
```

### 6 — Close the period, then send a late event

```bash
curl -s -X POST 'localhost:8080/api/v1/invoices/close?customerId=customer-42&period=2026-08' \
  -H 'X-Tenant-Id: tenant-a'
```

A transaction for August arriving now is charged in the open period as an adjustment,
while August's closed invoice stays exactly as it was.

---

## Architecture

### Module layout

```
com.revenium.usage
├─ ingestion       accepting transactions, idempotency, evidence
├─ rating          applying pricing rules, monetary calculation
├─ pricing         effective-dated pricing rules
├─ invoicing       billing periods, summaries, period close
├─ reconciliation  processing states, reports, traceability
├─ tenancy         TenantContext, @RequiresTenant, aspect, RLS plumbing
├─ processing      outbox, worker, retries, dead letters
└─ shared          domain primitives, errors, configuration
```

Each module is sliced the same way:

```
<module>
├─ api                        controllers, DTOs
├─ application                @Service, transaction boundaries
├─ domain                     depends on nothing
│  ├─ model                   plain Kotlin, no framework annotation, not even JPA
│  ├─ port/in                 one inbound port per use case
│  └─ port/out                only the operations the module needs
└─ infrastructure/persistence @Entity mirroring the table, plus its adapter
```

**The `domain` layer depends on nothing else in the project**, which is what lets the
rating rules, monetary arithmetic and period boundaries be unit-tested with no Spring
context and no database. The `@Entity` never leaves `infrastructure/persistence`: it
mirrors the *table* in primitives and converts through `toDomain()`/`fromDomain()`, so
the model is free to hold typed values and enforce its invariants in `init`.

Cross-module calls go through a port owned by the *consuming* module, never a whole
`JpaRepository` — a read-only consumer does not get `save` and `deleteAll`.
[`docs/diagrams/architecture.md`](docs/diagrams/architecture.md) has the full table of
ports and the two adapters that deliberately stayed raw JDBC.

### Data model

| Table | Role | Key invariant |
| --- | --- | --- |
| `raw_event` | The payload exactly as it arrived. Immutable | `UNIQUE (tenant_id, event_id)` |
| `rejected_event` | Validation failures, with reasons | written in its own transaction |
| `event_conflict` | Same event id, different body | first delivery always wins |
| `pricing_rule` | Effective-dated unit price | `EXCLUDE` prevents overlapping validity |
| `rated_transaction` | The calculated charge | partial unique index: one current row per event |
| `outbox_message` | Durable work queue | one queue entry per event |
| `invoice` / `invoice_line` | Frozen totals for a closed period | `CHECK (total = current + adjustment)` |

The split that matters is **evidence** (`raw_event`, never modified) from **result**
(`rated_transaction`, recomputable). A correction inserts a new rated row and points the
old one at it through `superseded_by`; nothing is ever updated in place, so the history
of what was billed and why stays intact.

`rated_transaction` records the `pricing_rule_id` **and** a copy of the `unit_price`.
The redundancy is deliberate: when a rule is later corrected, every amount calculated
under it must keep explaining itself without depending on the current pricing table.

### Transaction boundaries

| Boundary | Propagation | Isolation | Why |
| --- | --- | --- | --- |
| Ingestion (`EventRecorder`) | `REQUIRES_NEW` | `READ_COMMITTED` | event + outbox row commit together |
| Duplicate resolution | `REQUIRES_NEW` | `READ_COMMITTED` | needs a clean session after a constraint violation |
| Rejection record | `REQUIRES_NEW` | `READ_COMMITTED` | evidence must survive the caller's rollback |
| Rating (per message) | `REQUIRES_NEW` | `READ_COMMITTED` | one poison message must not roll back its batch |
| Period close | `REQUIRED` | `REPEATABLE_READ` | header and lines must come from one snapshot |

Ingestion writes the event and its outbox row in **one** transaction. That is the whole
guarantee of the outbox pattern: there is no window in which an event is accepted but its
rating work is lost.

`REQUIRES_NEW` on ingestion is not decorative. A unique-constraint violation marks the
surrounding transaction **rollback-only**, so catching the exception and carrying on still
fails at commit — and leaves the Hibernate session unusable, since the rejected entity
keeps a null identifier and poisons the next flush. Isolating the insert means a duplicate
is an ordinary result rather than a poisoned unit of work.

### Asynchronous processing

**Outbox table polled with `SELECT … FOR UPDATE SKIP LOCKED`.**

`SKIP LOCKED` makes a second worker step over rows another worker already holds, so any
number of instances take **disjoint** batches with no leader election, no distributed lock
and no broker.

A broker was considered and rejected: publishing to Kafka and writing to Postgres are not
atomic, so a correct implementation needs an outbox table *anyway*. Adding the broker would
add a container and several failure modes without adding correctness at this scale.

**Why not `@TransactionalEventListener(AFTER_COMMIT)`?** It solves the ordering problem —
the listener runs only once the transaction has committed, so it never observes a row that
is later rolled back, which is exactly the failure mode `@Async` inside a transactional
method runs into. That much it shares with the outbox. What it does not survive is a
crash:

| | `AFTER_COMMIT` | Outbox |
| --- | --- | --- |
| Work is | in memory, in this JVM | a committed row |
| JVM dies after commit, before the work runs | **silently lost** — nothing records that it was owed | claimed again after the stale-claim timeout |
| Second instance | cannot see the work | claims it with `SKIP LOCKED` |
| Retry / dead-letter | hand-rolled per listener | `attempt_count`, backoff, `FAILED` |
| Visible to an operator | no | `SELECT … FROM outbox_message` |

For a notification the loss would be a nuisance. Here the lost work is *rating a
transaction that has already been accepted and acknowledged with `202`* — the money is
simply never billed, and no query would reveal it, because the only evidence the work was
owed died with the process. That is the invariant this service exists to hold, so the work
has to be as durable as the event that created it.

`AFTER_COMMIT` remains the right tool where losing the work is acceptable: cache
invalidation, a metric, an email that can be re-sent.

**Guarantees and limits**, explicitly:

- **At-least-once, never exactly-once.** A worker that dies mid-transaction releases its
  locks and the rows become claimable again. Safe only because `rated_transaction` carries
  a partial unique index — the idempotency is in the database, not in the worker.
- **Ordering is not preserved** across workers. Acceptable here: rating one event never
  depends on another. Per-customer ordering would require partitioning the claim.
- **Retries** use exponential backoff, then dead-letter as `FAILED`. Nothing is discarded.
- **`UNRATED` is not a failure.** A missing pricing rule does not count against the retry
  budget: the rule may be created tomorrow, and the event must still be waiting when it is.
- **Abandoned claims** are reclaimed after a timeout, so a killed worker loses no work.
- **Throughput** was measured, not estimated, and the estimate was wrong twice over. A
  worker is bounded by `batchSize / pollInterval`, not by per-message cost: at the
  original 1 s interval that was 50 events/s and the workers were *idle*. Tuning the two
  values gained **6.3×** on a single instance (36 → 228 ev/s); tripling the instances on
  top of that gained **12%**, because the constraint had moved to the shared database.
  The defaults now ship at 200 ms and 200 per batch. Six measured runs, and where the
  ceiling actually is, in [docs/analysis/performance.md](docs/analysis/performance.md).

### Idempotency

Duplicate detection is an `INSERT` that catches the unique-constraint violation, **not** a
`SELECT` followed by an `INSERT`. A read-then-write has a race window that concurrent
delivery will find, and at that point both callers believe they are first. The database
arbitrates instead.

An integration test releases ten threads simultaneously on the same event id and asserts
exactly one `ACCEPTED`, nine `DUPLICATE`, one row and one unit of work.

**A duplicate returns `200`, not `409`.** Re-delivery is the upstream retry working exactly
as intended, not a client error; a `4xx` would prompt integrations to retry or alert over
correct behaviour. The `status` field distinguishes the cases and duplicates are visible in
reconciliation. This is a judgement call and reasonable people differ.

**Same event id with a different body** is a defect upstream that cannot be resolved
automatically: accepting the second delivery would double-bill, discarding it silently
would hide a real problem. The first delivery wins, the discrepancy is recorded in
`event_conflict`, and the reconciliation report surfaces it for a human.

### Tenant isolation

Three independent layers, because the brief is explicit that a `tenantId` in the payload is
not sufficient — the payload is entirely under the caller's control.

1. **`X-Tenant-Id` header → `TenantContext`.** The header is the only source of identity. A
   `tenantId` in the body is data to validate against it; a mismatch is rejected. In
   production this would be a gateway or a JWT claim, and that swap is confined to one class.
2. **`@RequiresTenant` + `TenantGuardAspect`.** Fails fast at service boundaries.
3. **PostgreSQL row-level security.** The backstop.

**The application connects as `usage_app`, which has `NOBYPASSRLS` and owns no tables.**
Both a superuser and a table owner bypass RLS unconditionally — even with
`FORCE ROW LEVEL SECURITY` — so the connecting role is the whole mechanism. Liquibase
connects separately as the owner to run migrations.

Consequence: **a query that forgets its tenant filter returns nothing rather than leaking**.
An integration test issues a deliberately unfiltered `SELECT` and asserts zero foreign rows
while two tenants' rows exist.

#### AOP specifics the brief asks about

- **Pointcut.** `@annotation(RequiresTenant) || @within(RequiresTenant)` — individual
  methods and every public method of an annotated class. Applied at the *service* boundary,
  where a unit of work begins and there is context for a useful error.
- **Advice ordering.** `HIGHEST_PRECEDENCE + 100`, ahead of transaction advice. A
  cross-tenant call is rejected before a transaction is opened and before a connection is
  used. Ordering it after would still be correct but would burn a pooled connection on every
  rejected call.
- **Proxy and self-invocation.** Spring AOP is proxy-based, so `this.annotatedMethod()`
  from inside the same class **bypasses the proxy and the aspect does not run**. This is
  proven by a test rather than assumed away — and the same test shows RLS still returns
  nothing, which is precisely the argument for defence in depth. `AopContext.currentProxy()`
  and load-time weaving were both rejected as complexity that does not pay.
- **Async propagation.** A `ThreadLocal` does not cross a thread pool boundary. The outbox
  worker re-establishes scope from the tenant on each claimed row via
  `TenantContext.runAs(…)`; `TenantAwareTaskDecorator` captures at submission time for
  `@Async` work. **Nothing is inherited implicitly** — a half-inherited context is worse
  than none.

#### The worker's cross-tenant exemption

The worker must discover work across tenants, which is exactly what RLS prevents. Three
options, only one acceptable:

1. Run the worker with `BYPASSRLS` — rejected: one bug in the worker then reads everything.
2. Loop over known tenants — rejected: needs a registry, scales with tenant count, starves
   tenants at the end of the list.
3. **Narrow the exemption to what the claim needs** — chosen.

A policy lets a connection with **no** tenant set see outbox rows and read events, and
nothing else: it cannot write a charge or read any other tenant-scoped table. Each message
is then rated inside `runAs(…)` with the tenant from its own row.

### Late-arrival policy

**A closed invoice is immutable. A transaction arriving after its period closed is rated at
its original period's price and charged as an adjustment in the open period.**

| Policy | For | Against |
| --- | --- | --- |
| **Adjustment in the next open period** | A customer never sees a figure change after being billed. Standard accounting practice. No recomputation | A period's total no longer equals that period's consumption |
| Reopen and version the invoice | Each period reflects its real consumption | A customer who already paid receives a different invoice |
| Reject outside a window | Trivial | Loses real revenue over an integration's failure |

The trade-off is real, so the summary reports `currentPeriodAmount` and `adjustmentAmount`
**separately** rather than hiding the adjustment inside a total. With `origin_period` on
every rated row, August's true consumption is still a single query away.

**Cutoff** (`BILLING_LATE_ARRIVAL_MAX_AGE`, default 90 days): beyond it an event is
`QUARANTINED` — neither billed nor discarded — for a human to decide, because an event that
old almost always means an accidental replay.

The cutoff measures **`receivedAt − occurredAt`**, not the event's age. Measuring age would
quarantine a deliberate reprocess of six-month-old data purely because the usage is old,
even though it arrived on time. The question the cutoff asks is *"did this only just turn
up?"*, and `receivedAt` is the field that answers it.

### Monetary correctness

- `BigDecimal` in Kotlin, `NUMERIC` in PostgreSQL. **No `Double` or `Float` anywhere near an
  amount.** Unit prices at scale 6, amounts at scale 4.
- **Rounded once**, `HALF_UP`, per transaction, when the amount is produced. Never on an
  intermediate, never on a total.
- An invoice total is the **sum of already-rounded line amounts**, and each line is the sum
  of its transactions' amounts. No step rounds twice, so the arithmetic closes exactly at
  every level — a total always equals the sum of its own lines.
- Comparison uses `compareTo`, never `equals`: `BigDecimal("2.0") != BigDecimal("2.00")`.

Pricing rule validity is `[effectiveFrom, effectiveTo)` — **start inclusive, end exclusive**.
The half-open interval removes ambiguity at the changeover instant, which is where
off-by-one-cent bugs live. Billing periods use the same convention.

### Reconciliation

Every received event is in exactly one state — `REJECTED`, `DUPLICATE`, `ACCEPTED`,
`UNRATED`, `RATED`, `INVOICED`, `FAILED`, `QUARANTINED` — derived from the tables rather
than stored on a status column, so there is no second copy of the truth to drift.

The report evaluates its own arithmetic and says when it fails:

```
received = accepted + duplicates + rejected
accepted = rated + invoiced + unrated + failed + quarantined
```

`balanced: false` means a defect in the service, not an accounting subtlety, and
`imbalance` names the equation that failed.

Tracing a figure takes three steps: the summary line names a code and a total, `/lines`
returns the transactions behind it with quantity, unit price and rule id, and
`/pricing-rules/{id}` returns the rule. An integration test walks exactly that path and
re-derives every amount.

---

## API

All endpoints require `X-Tenant-Id`. Errors are RFC 7807 `application/problem+json`; a
stack trace never reaches a client.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/transactions` | Ingest one transaction |
| `POST` | `/api/v1/transactions/batch` | Ingest a batch, per-item results |
| `GET` | `/api/v1/invoices/summary` | Invoice summary for a customer and one period |
| `GET` | `/api/v1/invoices/usage` | Usage totals across a range of periods (`from`/`to`) |
| `POST` | `/api/v1/invoices/close` | Close a period (administrative) |
| `GET` | `/api/v1/reconciliation/report` | State counts and balance check |
| `GET` | `/api/v1/reconciliation/lines` | Transactions behind a total |
| `GET` | `/api/v1/pricing-rules` | The tenant's rules |
| `GET` | `/api/v1/pricing-rules/{id}` | One rule, to verify an amount |
| `GET` | `/actuator/health` | Liveness and readiness |

### Two summaries, and why they are separate endpoints

`/invoices/summary` answers a billing question about one period, and carries that
period's status. `/invoices/usage` answers a reporting question across a range, taking
`from` and `to` as `YYYY-MM`, inclusive.

They are separate rather than one endpoint with optional range parameters because a range
can straddle closed and open periods, so there is no single status to report — the range
response lists each period with its own. Calling that result an invoice would be a lie
about what it is.

The range is built by summarising each period in turn, not with one wide query. That
costs one query per month, and it is what guarantees a closed period contributes exactly
the figures it was billed at: a single `GROUP BY` over `rated_transaction` would silently
re-aggregate closed months and could disagree with an invoice already sent. Bounded to 24
months, since the cost is linear in the span and anything wider is a data export.

### Contract extensions

Two changes to the contract in the brief, both **additive and backward compatible**:

1. `quantity` and `currency` accepted at the top level, with `metadata.quantity` as a
   fallback. A producer using the original shape keeps working unchanged.
2. Unknown fields are ignored, so upstream can add fields without a coordinated deployment.

**`X-Tenant-Id` is required**, which *is* breaking for a client that only sent `tenantId` in
the body. Deliberate: the brief states the payload is not sufficient. Migration is one header.

---

## Testing

| Level | Tool | Scope |
| --- | --- | --- |
| Unit | JUnit 5 + MockK | Rating, rounding, effective dating, period boundaries. No Spring |
| Persistence | Testcontainers | Constraints, RLS, migrations |
| Web slice | `@WebMvcTest` | Contracts and status codes |
| Full context | `@SpringBootTest` | AOP, transactions, outbox, concurrency |

Integration tests are tagged `integration` and excluded from `./gradlew test`, so the
**coverage gate measures unit-test coverage only** — a full-context test would otherwise
inflate it with code that was merely touched rather than verified.

### Two standard annotations this project does not use

Both are the obvious choice here, and both were tried. Each would have made a test pass
while proving less than it appears to.

**`@ServiceConnection`** wires the application to the Testcontainers container's
**superuser** — and a superuser bypasses row-level security unconditionally, even under
`FORCE ROW LEVEL SECURITY`. Every tenant-isolation test would have passed without the
policies doing anything. This is not hypothetical: the first run of this suite leaked
tenant-a's rows into a tenant-b query, and the cause was the connecting role, not the
policies.

So the roles mirror production instead. Liquibase connects as the owner, because it
creates extensions and tables; the application connects as `usage_app`, which owns
nothing and has `NOBYPASSRLS`, so the policies apply to it exactly as they do in
production. The wiring is a small `ApplicationContextInitializer`
(`support/IntegrationTest.kt`) — `@DynamicPropertySource` is also unusable here, because
it is only honoured on the test class itself and is silently ignored on an `@Import`ed
`@TestConfiguration`.

**`@DataJpaTest`** replaces the datasource and rolls each test back by default. Neither
fits what the persistence layer here actually needs proving: the constraints are the
enforcement mechanism (`EXCLUDE USING gist`, the partial unique index, RLS policies), and
they only exist in a real PostgreSQL with the right role connected. A slice against an
embedded database would test Hibernate's mapping, which is not where the risk is.

The mapping is still covered without Spring at all: each entity has round-trip unit tests
(`*/infrastructure/persistence/*EntityTest.kt`) asserting that domain → entity → domain
loses nothing. Those run in the unit suite and count toward the coverage gate; the
constraint behaviour runs against Testcontainers.

The four behaviours the brief names explicitly:

- **AOP applies** — and self-invocation demonstrably bypasses it, with RLS still blocking
  the data.
- **Rollback** — an accepted event and its outbox row survive a failure in the caller,
  because every ingestion write is `REQUIRES_NEW`: once the upstream has been told `202`
  it will not re-deliver, so un-accepting the event would lose it. The rejection record
  survives the same way, and a duplicate's failed insert rolls back without disturbing
  the original.
- **Idempotency** — ten concurrent threads on one event id; four concurrent workers on
  twenty events; exactly one charge each time.
- **Tenant isolation** — at the aspect, at the repository, and at the database, including a
  deliberately unfiltered query that returns nothing.

### Coverage gate

`check` depends on `jacocoTestCoverageVerification`, which fails below **85% line coverage**.
Verified in both directions: it fails when production code is uncovered and passes when it
is covered.

**Exclusions**, limited to framework bootstrap and pure wiring:

| Excluded | Why |
| --- | --- |
| `UsageRatingApplication` | `@SpringBootApplication` class containing only `main()` |
| `shared/config/**` | `@Configuration` classes that only declare beans |

Domain, service, rating, tenancy and reconciliation code are all inside the gate.

---

## Configuration

Everything is environment-overridable; defaults are for local development only. No
credentials are committed.

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/usage_rating` | Application datasource |
| `DB_USER` / `DB_PASSWORD` | `usage_app` | Runtime role — **no `BYPASSRLS`, owns nothing** |
| `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` | falls back to `DB_USER` | Liquibase role (owner) |
| `BILLING_LATE_ARRIVAL_MAX_AGE` | `P90D` | Beyond this, late events are quarantined |
| `OUTBOX_POLL_INTERVAL` | `1000ms` | Worker poll cadence |
| `OUTBOX_MAX_ATTEMPTS` | `5` | Attempts before dead-lettering |
| `SHUTDOWN_TIMEOUT` | `20s` | Grace period for in-flight work on `SIGTERM` |

Graceful shutdown is enabled: on `SIGTERM` the worker stops claiming new work and finishes
its current batch. Unclaimed work stays `PENDING`; claimed-but-unfinished work is reclaimed
by another instance after the stale-claim timeout.

---

## Assumptions and scope

Simplified, as the brief permits:

- **Authentication.** No login or JWT; the tenant arrives in a header assumed to be set by
  an authenticating gateway. The extension point is one class.
- **Customer management.** `customerId` is an opaque string. No catalogue, no validation.
- **Invoice lifecycle.** Two states, `OPEN` and `CLOSED`, closed by an administrative
  endpoint rather than a scheduler so the behaviour is demonstrable. No issuing, payment,
  credit notes or tax.
- **Pricing.** Per-unit price by `(tenant, transactionCode)`. No tiers, minimums, caps or
  volume discounts — the model leaves room for them without a destructive migration.
  Pricing is read-only over the API; price changes are an administrative operation.
- **Currency.** One currency per pricing rule, no FX conversion.

Not sacrificed: no double billing, every amount explainable, no cross-tenant access,
append-only history, decimal arithmetic with explicit rounding.

### Paid for, not simplified away

**The persistence layer is separate from the domain.** Each module's `domain/model` is
plain Kotlin over the typed values (`Money`, `Quantity`, `BillingPeriod`); the `@Entity`
mirroring the table lives in `infrastructure/persistence` behind an out-port, with a
`toDomain()`/`fromDomain()` pair.

The mapping is boilerplate and the cost is real. It buys a domain that unit-tests with
no Spring context and cannot be quietly reshaped by a mapping concern. Eight models now
reject their own invalid states in `init` — an invoice whose total is not
`currentPeriod + adjustment`, a late adjustment billed in its own origin period, a
conflict whose two payload hashes are equal. Every
mapper is covered by a round-trip test: one that silently drops a field would otherwise
surface only in an audit, with the amount written under the wrong period.

### Decisions a reviewer might have made differently

These are settled, not open. Each is implemented, tested and argued; they are listed
because the alternative is defensible and the reasoning is worth stating rather than
leaving to be inferred.

- **A duplicate returns `200`, not `409`.** Re-delivery is the upstream retry working as
  intended, and a 4xx tells an integrator to back off or alert over something that
  behaved correctly. The `status` field distinguishes the cases and reconciliation counts
  every re-delivery. A reviewer who treats "this event id already exists" as a client
  error would choose `409`; that is a one-line change and the tests state the current
  contract explicitly.
- **Period close is an administrative endpoint, not a scheduler.** A scheduled close is
  more realistic in production. An endpoint is demonstrable in a review, and it makes the
  close a deliberate act in the evidence transcripts rather than something that happens
  while nobody is looking. The scheduler would wrap this same endpoint.

### Open questions

One genuine ambiguity, which the brief does not settle and which I did not want to
resolve by silently picking an answer:

- **A customer with transaction codes priced in different currencies.** Today one
  currency per pricing rule, and an invoice carries a single currency — so such a
  customer's invoice would be wrong rather than merely limited. Handling it honestly
  means per-currency invoice lines and totals, which is a schema change, and it raises a
  policy question this exercise cannot answer alone: whether that customer receives one
  invoice per currency or one invoice with several currency sections.

  The failure mode today is loud rather than silent: `Money.plus` rejects operands of
  different currencies, so summarising such a customer throws instead of producing a
  total that adds euros to dollars. That is the right behaviour for an unanswered
  question — it refuses rather than guesses — but it is a refusal, not support, and the
  modelling decision belongs to whoever owns the billing policy.

---

## Notes from building this

A few findings that cost real time and are worth knowing:

- **Spring Boot 4 uses Jackson 3** (`tools.jackson`), so `com.fasterxml…ObjectMapper` is not
  a bean. `spring-boot-starter-aop` no longer exists (it is `starter-aspectj`), Liquibase
  autoconfiguration moved to a separate `spring-boot-liquibase` module — without it
  migrations are **silently skipped** — and `TestRestTemplate` moved packages.
- **`@DynamicPropertySource` is ignored on an `@Import`ed `@TestConfiguration`.** It only
  works on the test class itself. Declared elsewhere it fails silently and surfaces much
  later as an authentication error.
- **PostgreSQL does not round-trip `NULL` through `set_config`**: writing `NULL` yields an
  **empty string**, and only a never-written setting reads back as `NULL`. Policies written
  against `IS NULL` stop matching once the setting is cleared.
- **Liquibase's `valueDate` discards the timezone offset** and stores the local-time
  equivalent. On a UTC−5 machine a seeded price changeover landed at 05:00 UTC instead of
  midnight — five hours of events billed at the wrong price, invisible in the seed file and
  caught only by asserting on a boundary event. Seeds now use explicit `TIMESTAMPTZ` literals.

Three defects only appeared when the finished service was exercised through Docker
Compose rather than through tests, all from one scenario — **closing the period that is
still in progress**:

- **The late adjustment had nowhere to go.** "The open period" was taken to mean the one
  containing *now*, which in this scenario was the period just closed. The adjustment was
  assigned the same period as its origin, the `CHECK` requiring them to differ rejected
  the insert, and the charge was lost. The search now walks forward to the first period
  that is genuinely open.
- **The worker reported success for a write that never happened.** Every
  `DataIntegrityViolationException` was treated as a lost concurrency race, so the failed
  insert above was recorded as `DONE`: an accepted event, silently never billed, with
  nothing explaining why. Only a violation naming the current-rating unique index is a
  race now; anything else propagates and retries.
- **The reconciliation report could never balance for an adjustment.** It counted events
  by `occurred_at` but charges by `billing_period` — two different periods for the same
  transaction, so one side always came up short. The report now counts charges by
  `origin_period`, which is the same instant as `occurred_at`. `/lines` still uses
  `billing_period`, because tracing an invoice figure is a different question.

None of these were reachable from the unit tests, and only the first was reachable from
the integration suite as originally written. All three now have regression tests.

Three more surfaced only once the finished service was running under Docker Compose:

- **A Kotlin `@JvmInline value class` as an injected constructor parameter** makes the
  compiler emit a synthetic `DefaultConstructorMarker` that Spring tries to autowire.
  Every unit test passed; the container would not start.
  `ApplicationContextIntegrationTest` now asserts the context boots and that the tenant
  guard is actually proxied.
- **A malformed `period` query parameter returned 500 instead of 400**, telling an
  integrator to retry and an operator to investigate when neither was right.
- **`InvoicingIntegrationTest` was order-dependent**: tests shared one customer, so a
  test that closed a period moved another test's charges from `RATED` to `INVOICED`. It
  passed alone and failed in the suite.

A review of the production sources then found three dependency-rule violations —
rating taking the outbox's JDBC projection, invoicing injecting rating's JPA repository,
ingestion injecting the outbox's. Two of them also handed a module **write access** it
had no business holding: invoicing could have deleted rated transactions, ingestion
could have emptied the work queue. Each is now a port owned by the consuming module, and
`DependencyRuleTest` fails the build if any regresses.

Two more the local build could not have found, because both need a *fresh clone* rather
than a working tree:

- **`.gitignore` excluded every outbound port.** It carried `out/` for IntelliJ's build
  directory; unanchored, that matches any directory named `out` at any depth, so all
  eight `domain/port/out` packages were silently never committed. Locally the files
  exist and everything compiles. CI, which clones, failed with `Unresolved reference
  'out'`. Anchored to `/out/`, and nothing under `src/` is ignored now.
- **The aggregating check reported success while compilation was failing.** The compile
  job was missing from its `needs`, and it counted a *skipped* job as acceptable — so
  when compilation broke and its five dependents skipped, the one status check branch
  protection requires went green having verified nothing. Only the PR-only
  multi-instance job may skip now; any other skip fails the check, and the logic is
  tested against the exact shape that slipped through.

And one test that was passing for the wrong reason: the multi-instance distribution
assertion compared how many instances claimed work, with a batch size (200) larger than
the workload (60). The first worker to wake legitimately took everything. Locally the
containers are warm and overlap, so it usually split and passed; on a cold runner one
worker won every time. It was measuring startup order, not coordination. CI now runs it
with a batch of 10, and the script reports the batch size and says so when the batch is
too large to observe distribution rather than blaming `SKIP LOCKED`.
