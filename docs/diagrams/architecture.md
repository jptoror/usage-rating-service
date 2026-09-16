# Architecture

Diagrams render on GitHub and in any Mermaid-aware viewer.

---

## System context

What the service talks to, and what it owns.

```mermaid
flowchart LR
  UP["Upstream<br/>integration"] -->|"POST /transactions<br/>X-Tenant-Id"| SVC
  OPS["Operator"] -->|"close period<br/>read reports"| SVC
  AUD["Reviewer /<br/>auditor"] -->|"trace an amount"| SVC

  subgraph SVC["Usage Rating Service"]
    API["REST API"]
    WORK["Outbox worker"]
  end

  SVC --> PG[("PostgreSQL 16<br/>system of record")]

  style SVC fill:#e8f0fe,stroke:#4285f4
  style PG fill:#fce8e6,stroke:#ea4335
```

The database is not an implementation detail here: uniqueness, non-overlapping pricing
validity and tenant isolation are all enforced in PostgreSQL, so the service cannot
violate them even with a bug in the application layer.

---

## Modules and dependencies

```mermaid
flowchart TD
  subgraph edge["Edge"]
    API["api<br/>controllers, DTOs"]
  end

  subgraph app["Use cases"]
    ING["ingestion"]
    RAT["rating"]
    INV["invoicing"]
    REC["reconciliation"]
    PROC["processing<br/>outbox worker"]
  end

  subgraph dom["Domain — depends on nothing"]
    MONEY["shared<br/>Money, BillingPeriod, ids"]
    PRICE["pricing<br/>PricingRule"]
    TEN["tenancy<br/>TenantContext, aspect"]
  end

  INFRA["infrastructure<br/>JPA, JDBC, RLS plumbing"]

  API --> ING & INV & REC
  PROC --> RAT
  ING & RAT & INV & REC --> MONEY
  RAT --> PRICE
  ING & RAT & INV & REC --> TEN
  INFRA --> MONEY & PRICE

  style dom fill:#e6f4ea,stroke:#34a853
  style INFRA fill:#fef7e0,stroke:#fbbc04
```

Arrows point inward. `domain` imports nothing from `api`, `application`,
`infrastructure`, or another module's internals — which is what lets the rating rules,
monetary arithmetic and period boundaries be unit-tested with no Spring context and no
database.

Cross-module calls go through a port owned by the **consuming** module — the module
states what it needs, and the providing module supplies it:

| Port | Owned by | Implemented by | What it prevents |
| --- | --- | --- | --- |
| `PricingRuleLookup` | `pricing.domain` | `pricing.infrastructure` | rating depending on JPA |
| `BillingPeriodStatusLookup` | `invoicing.domain` | `invoicing.infrastructure` | rating importing invoicing internals |
| `ChargeLookup` | `invoicing.domain` | `rating.infrastructure` | invoicing holding `save`/`delete` on the ledger |
| `RatingQueue` | `ingestion.domain` | `processing.infrastructure` | ingestion holding `delete` on the work queue |
| `RateableTransaction` | `rating.domain` | mapped by `processing` | rating being drivable only by the outbox |

Each of the last three replaced a direct dependency on another module's infrastructure.
Two of them also removed **write access** a module had no business holding: invoicing
could have deleted rated transactions, and ingestion could have emptied the outbox.
`DependencyRuleTest` now fails the build if any of these regress.

---

## Data model

```mermaid
erDiagram
  RAW_EVENT ||--o| RATED_TRANSACTION : "rated into"
  RAW_EVENT ||--|| OUTBOX_MESSAGE : "queues"
  RAW_EVENT ||--o{ EVENT_CONFLICT : "conflicting re-delivery"
  PRICING_RULE ||--o{ RATED_TRANSACTION : "priced by"
  RATED_TRANSACTION ||--o| RATED_TRANSACTION : "superseded by"
  INVOICE ||--o{ INVOICE_LINE : "contains"

  RAW_EVENT {
    bigint id PK
    text tenant_id "UNIQUE with event_id"
    uuid event_id "idempotency key"
    timestamptz occurred_at "period + rule lookup"
    timestamptz received_at "late-arrival detection"
    jsonb payload "verbatim evidence"
    bigint duplicate_delivery_count "the one mutable field"
  }

  PRICING_RULE {
    bigint id PK
    numeric unit_price "scale 6, never rounded"
    timestamptz effective_from "inclusive"
    timestamptz effective_to "exclusive, nullable"
  }

  RATED_TRANSACTION {
    bigint id PK
    bigint pricing_rule_id FK "which rule"
    numeric unit_price "and its value then"
    numeric amount "scale 4, rounded once"
    date billing_period "charged in"
    date origin_period "used in"
    bigint superseded_by FK "corrections append"
  }

  OUTBOX_MESSAGE {
    bigint id PK
    text status "PENDING PROCESSING DONE UNRATED FAILED QUARANTINED"
    int attempt_count
    text processed_by "which instance — evidence only"
  }

  INVOICE {
    bigint id PK
    text status "OPEN or CLOSED"
    numeric current_period_amount
    numeric adjustment_amount
    numeric total_amount "CHECK = current + adjustment"
  }
```

Two splits carry the design:

**Evidence vs. result.** `raw_event` holds what arrived and is never rewritten;
`rated_transaction` holds what was calculated and can be recomputed. A correction
inserts a new rated row and points the old one at it via `superseded_by`.

**Two periods.** `origin_period` is when the usage happened, `billing_period` is when it
is charged. They differ only for a late arrival, and a `CHECK` keeps the flag and the two
periods consistent so no code path can set one without the other.

---

## Invariants enforced by the database

Not by application code, so a bug in the service cannot violate them.

| Invariant | Mechanism |
| --- | --- |
| One event bills at most once | `UNIQUE (tenant_id, event_id)` |
| One current rating per event | partial unique index `WHERE superseded_by IS NULL` |
| Pricing rules never overlap | `EXCLUDE USING gist` over `tstzrange` |
| An invoice total matches its parts | `CHECK (total = current + adjustment)` |
| A late adjustment's periods differ | `CHECK (is_late = (billing <> origin))` |
| No tenant reads another's rows | row-level security on all eight tables |

---

## Deployment

```mermaid
flowchart TD
  LB["nginx<br/>:8080"]
  LB --> A1["app 1"] & A2["app 2"] & A3["app 3"]
  A1 & A2 & A3 --> PG[("PostgreSQL")]

  subgraph note[" "]
    N["Each instance runs its own worker.<br/>Coordination is FOR UPDATE SKIP LOCKED —<br/>no leader election, no lock service, no broker."]
  end

  style note fill:#f8f9fa,stroke:#dadce0
```

Two database roles, and the distinction is the whole of tenant isolation:

| Role | Used by | Privileges |
| --- | --- | --- |
| `usage_owner` | Liquibase | owns the tables, runs migrations |
| `usage_app` | the application | `NOBYPASSRLS`, owns nothing |

A superuser or a table owner bypasses row-level security unconditionally — even with
`FORCE ROW LEVEL SECURITY` — so the runtime role must be neither. Connecting as the owner
would leave every policy in place and enforcing nothing.
