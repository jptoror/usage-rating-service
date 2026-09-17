# Sequence diagrams

One per operation, with the transaction boundaries marked — those boundaries are where
most of the design decisions live.

---

## 1. Ingesting a transaction

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant F as TenantFilter
    participant A as TenantGuardAspect
    participant S as IngestionService
    participant R as EventRecorder
    participant DB as PostgreSQL

    C->>F: POST /transactions<br/>X-Tenant-Id: tenant-a
    F->>F: TenantContext.set(tenant-a)
    Note over F: header is the only source of identity —<br/>a tenantId in the body is data to check

    F->>A: ingest(input)
    A->>A: tenant in scope?
    Note over A: ordered BEFORE transaction advice,<br/>so a cross-tenant call never opens one

    A->>S: ingest(input)
    S->>S: validate — collects ALL failures

    alt invalid
        S->>DB: REQUIRES_NEW: insert rejected_event
        Note over DB: its own transaction, so the evidence<br/>survives the caller's rollback
        S-->>C: 422 REJECTED + every failure
    else valid
        S->>R: record(transaction)
        activate R
        Note over R,DB: TX: REQUIRES_NEW, READ_COMMITTED
        R->>DB: insert raw_event
        R->>DB: insert outbox_message
        Note over R,DB: both or neither — no window where<br/>an event is accepted but its work is lost
        deactivate R
        R-->>C: 202 ACCEPTED
    end
```

The response returns as soon as the event is durable. Rating happens later, on a worker.

---

## 2. A duplicate delivery

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as IngestionService
    participant R as EventRecorder
    participant D as DuplicateResolver
    participant DB as PostgreSQL

    C->>S: POST same eventId again
    S->>R: record(transaction)
    R->>DB: insert raw_event
    DB--xR: UNIQUE violation

    Note over R,DB: the failed transaction is now rollback-only<br/>AND its Hibernate session is unusable
    Note over R: which is why the insert runs in its own<br/>transaction — a duplicate must not poison the caller

    S->>D: resolve(transaction)
    activate D
    Note over D,DB: TX: REQUIRES_NEW — a clean session
    D->>DB: find the existing event
    D->>DB: increment duplicate_delivery_count
    Note over D,DB: an identical re-delivery writes nothing else,<br/>so without this tally it is invisible to reconciliation

    alt payload differs
        D->>DB: insert event_conflict
        Note over D: first delivery wins — billing the second<br/>would double-charge — but a human must see this
    end
    deactivate D

    D-->>C: 200 DUPLICATE
    Note over C: 200, not 409: the upstream retry worked<br/>exactly as intended. A 4xx would make<br/>integrations alert over correct behaviour.
```

---

## 3. Rating, across three instances

```mermaid
sequenceDiagram
    autonumber
    participant W1 as Worker (instance 1)
    participant W2 as Worker (instance 2)
    participant DB as PostgreSQL
    participant RS as RatingService

    par both poll at the same moment
        W1->>DB: SELECT ... FOR UPDATE SKIP LOCKED
        DB-->>W1: rows 1–50
    and
        W2->>DB: SELECT ... FOR UPDATE SKIP LOCKED
        DB-->>W2: rows 51–100
    end
    Note over DB: SKIP LOCKED steps over rows the other holds,<br/>so the batches are disjoint — no leader election,<br/>no distributed lock, no broker

    W1->>DB: mark PROCESSING, processed_by = instance-1

    loop each message, in its OWN transaction
        W1->>RS: rate(work)
        Note over RS: TenantContext.runAs(tenant from the ROW) —<br/>a ThreadLocal does not cross a thread pool

        RS->>DB: already rated? (fast path)
        RS->>RS: resolve the rule at occurredAt
        Note over RS: priced by WHEN IT HAPPENED, never by now,<br/>so a replay reproduces the original amount

        alt no rule covers it
            RS-->>W1: UNRATED
            W1->>DB: retry slowly, attempt_count NOT incremented
            Note over W1: a missing rule is a config gap, not a bad event —<br/>counting it would dead-letter real usage
        else arrived far too late
            RS-->>W1: QUARANTINED
            Note over W1: held for a human: neither billed nor discarded
        else priced
            RS->>DB: insert rated_transaction
            alt UNIQUE violation on the current-rating index
                Note over RS: another worker won the race
                RS-->>W1: ConcurrentRatingException → DONE
            else any OTHER integrity violation
                Note over RS: a real defect — propagate and retry.<br/>Treating these alike once lost a charge silently.
                RS-->>W1: rethrow → retry, then dead-letter
            end
            W1->>DB: mark DONE
        end
    end
```

Delivery is **at-least-once**, never exactly-once. A worker that dies mid-transaction
releases its locks and the rows become claimable again — safe only because
`rated_transaction` carries a partial unique index. The idempotency lives in the
database, not in the worker.

---

## 4. Summarising a period

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as InvoiceService
    participant DB as PostgreSQL

    C->>S: GET /invoices/summary?period=2026-09
    Note over S,DB: TX: readOnly

    S->>DB: is there an invoice for this period?

    alt period is CLOSED
        S->>DB: read invoice + invoice_line
        Note over S: read back verbatim — re-aggregating could<br/>report a different figure than the one billed
    else period is OPEN
        S->>DB: select rated_transaction for the period
        S->>S: group by (code, originPeriod)
        S->>S: SUM already-rounded amounts
        Note over S: summed, never recalculated: a second rounding<br/>would leave the total disagreeing with its own lines
    end

    S-->>C: currentPeriodAmount + adjustmentAmount = totalAmount
    Note over C: reported separately so a reader can tell<br/>this period's usage from what is merely billed in it
```

---

## 5. Closing a period, and the late arrival that follows

```mermaid
sequenceDiagram
    autonumber
    participant OP as Operator
    participant S as InvoiceService
    participant DB as PostgreSQL
    participant W as Worker
    participant RC as RatingCalculator

    OP->>S: POST /invoices/close?period=2026-09
    activate S
    Note over S,DB: TX: REPEATABLE_READ
    Note over S: one snapshot, so the header and its lines<br/>cannot disagree if a rating commits mid-aggregation
    S->>DB: aggregate rated transactions
    S->>DB: insert invoice (CLOSED) + invoice_line
    deactivate S
    S-->>OP: frozen totals

    Note over OP,DB: — later, a transaction for September arrives —

    W->>RC: rate(event from September)
    RC->>DB: is September closed?
    DB-->>RC: yes

    RC->>RC: walk forward to the first OPEN period
    Note over RC: not "the period containing now": an operator may<br/>close the period still in progress, and the adjustment<br/>would then land in the same period as its origin —<br/>which the database CHECK rejects outright

    RC-->>W: billingPeriod = October,<br/>originPeriod = September, isLate = true
    W->>DB: insert rated_transaction

    Note over DB: September's invoice is untouched.<br/>A customer never sees a figure move after being billed.
```

---

## 6. Tracing an amount back to its evidence

The path a reviewer walks. Three requests, and every figure re-derives by hand.

```mermaid
sequenceDiagram
    autonumber
    participant R as Reviewer
    participant INV as /invoices/summary
    participant REC as /reconciliation/lines
    participant PR as /pricing-rules/{id}

    R->>INV: what is owed for 2026-09?
    INV-->>R: VEHICLE_REGISTRATION: 5.0000 (1 transaction)

    R->>REC: which transactions produced that?
    REC-->>R: eventId 73d4e120…, quantity 2,<br/>unitPrice 2.500000, amount 5.0000,<br/>pricingRuleId 2

    R->>PR: what was rule 2?
    PR-->>R: 2.500000 USD, effective from 2026-07-01

    Note over R: 2 × 2.500000 = 5.0000 ✓<br/>Nothing in the system is a number<br/>without a derivation.
```

---

## 7. Where tenant isolation is enforced

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant F as TenantFilter
    participant A as Aspect
    participant SVC as Service
    participant DS as TenantAwareDataSource
    participant PG as PostgreSQL

    C->>F: X-Tenant-Id: tenant-a
    F->>F: TenantContext.set(tenant-a)

    F->>A: call a @RequiresTenant method
    A->>A: layer 1 — tenant in scope? argument matches?
    A->>SVC: proceed

    SVC->>DS: borrow a connection
    DS->>PG: set_config('app.tenant_id', 'tenant-a', false)
    Note over DS: ALWAYS written, as NULL when unscoped.<br/>Returning early once left the PREVIOUS borrower's<br/>tenant on a pooled connection — the exact leak<br/>RLS exists to prevent

    SVC->>PG: SELECT ... (even with no WHERE tenant_id)
    PG->>PG: layer 2 — row-level security filters
    PG-->>SVC: only tenant-a's rows

    Note over PG: the app connects as usage_app: NOBYPASSRLS<br/>and owns nothing. A superuser or table owner<br/>bypasses every policy unconditionally.
```

The aspect fails fast with a clear error; row-level security is what holds when the
aspect does not run at all — as with self-invocation, which bypasses the Spring proxy
entirely and is proven to do so by a test.
