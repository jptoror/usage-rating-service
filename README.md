# Usage Rating and Invoice Reconciliation Service

Ingests customer usage transactions, rates them under effective-dated pricing rules,
produces invoice summaries, and exposes reconciliation evidence.

> **Status: scaffolding.** Build, configuration, module layout and the coverage gate
> are in place and verified. Domain implementation is in progress — see
> [Implementation status](#implementation-status).

## Requirements

- JDK 21 (the build declares a toolchain; `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`)
- Docker, for integration tests and for `docker compose`

## Quick start

```bash
cp .env.example .env          # adjust credentials
docker compose up --build     # Postgres + app, migrations applied on startup
```

The service listens on `http://localhost:8080`:

| | |
| --- | --- |
| Health | `http://localhost:8080/actuator/health` |
| OpenAPI | `http://localhost:8080/v3/api-docs` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |

## Build and test

```bash
./gradlew check              # compile + unit tests + integration tests + coverage gate
./gradlew test               # unit tests only, no Docker needed
./gradlew integrationTest    # integration tests only, requires Docker
./gradlew jacocoTestReport   # -> build/reports/jacoco/test/html/index.html
```

`./gradlew check` is the single command that builds and verifies everything.

### Coverage gate

`check` depends on `jacocoTestCoverageVerification`, which fails the build below **85%
line coverage** measured on **unit tests only**. Integration tests are deliberately
excluded from the measurement so that coverage cannot be inflated by starting a full
application context and touching code incidentally.

The gate was verified in both directions: it fails when production code is uncovered,
and passes once it is covered.

**Exclusions**, limited to framework bootstrap and pure wiring:

| Excluded | Why |
| --- | --- |
| `UsageRatingApplication` | `@SpringBootApplication` class containing only `main()` |
| `shared/config/**` | `@Configuration` classes that only declare beans |

Domain, service, rating, tenancy and reconciliation code are all inside the gate.

## Architecture

The design document behind this implementation covers the data model, transaction
boundaries, asynchronous processing, idempotency, tenant isolation and the
late-arrival policy in full. Summary:

| Concern | Decision |
| --- | --- |
| Async coordination | Outbox table in Postgres, workers polling with `FOR UPDATE SKIP LOCKED` |
| Idempotency | `UNIQUE (tenant_id, event_id)`; insert-and-catch, never read-then-write |
| Tenant isolation | `X-Tenant-Id` header → `TenantContext` → `@RequiresTenant` aspect → Postgres RLS |
| Late arrival | Closed periods stay immutable; late events bill as an adjustment in the open period |
| Pricing | Effective-dated rules, `[from, to)`, non-overlap enforced by a Postgres `EXCLUDE` constraint |

### Module layout

```
com.revenium.usage
├─ ingestion       # accepting transactions, idempotency, raw event persistence
├─ rating          # applying pricing rules, monetary calculation
├─ pricing         # effective-dated pricing rules
├─ invoicing       # billing periods, summaries, period close
├─ reconciliation  # processing states, reports, traceability
├─ tenancy         # TenantContext, @RequiresTenant, aspect, RLS plumbing
├─ processing      # outbox, worker, retries, dead letters
└─ shared          # cross-cutting: domain primitives, errors, configuration
```

Each module is layered `api → application → domain`, with `infrastructure → domain`.
The `domain` layer depends on nothing else in the project.

## Configuration

Everything is environment-overridable; defaults in `application.yml` are for local
development only. No credentials are committed.

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/usage_rating` | Application datasource |
| `DB_USER` / `DB_PASSWORD` | `usage_app` | Application role — **no `BYPASSRLS`** |
| `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` | falls back to `DB_USER` | Liquibase role (table owner) |
| `BILLING_LATE_ARRIVAL_MAX_AGE` | `P90D` | Beyond this, late events are quarantined |
| `OUTBOX_POLL_INTERVAL` | `1000ms` | Worker poll cadence |
| `OUTBOX_MAX_ATTEMPTS` | `5` | Attempts before a message is dead-lettered |

The application and the migrations connect as **different roles by design**: a table
owner bypasses row-level security in Postgres, so the runtime role must not be the owner.

## Technology

Kotlin 2.4.20 · Spring Boot 4.1.1 · PostgreSQL 16 · Liquibase · Gradle 8.14.4 (Kotlin
DSL, wrapper committed) · JUnit 5 · MockK · Testcontainers · JaCoCo

## Implementation status

- [x] Gradle build, version catalog, coverage gate (verified failing and passing)
- [x] Module layout and layering rules
- [x] Configuration, Docker, Compose, Postgres roles for RLS
- [ ] Liquibase schema and seed pricing rules
- [ ] Ingestion, idempotency
- [ ] Tenancy: context, aspect, RLS policies
- [ ] Rating and the outbox worker
- [ ] Invoicing and late-arrival handling
- [ ] Reconciliation reporting
- [ ] Full test suite and API documentation
