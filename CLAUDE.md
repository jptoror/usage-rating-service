# CLAUDE.md

Guidance for working in this repository.

## What this is

A greenfield usage rating and invoice reconciliation service, built as a take-home
exercise for a Senior Enterprise Backend Engineer position. It ingests customer usage
transactions, rates them against effective-dated pricing rules, produces invoice
summaries, and exposes reconciliation evidence.

**The service is the system of record for accepted usage and its rating result.
Financial correctness and traceability outrank throughput.** When a change trades
correctness for speed, it is the wrong change.

## Stack

| Concern | Choice |
| --- | --- |
| Language | Kotlin 2.4.20, JVM target 21 |
| Framework | Spring Boot 4.1.1 (Spring Framework 7) |
| Build | Gradle 8.14.4, Kotlin DSL, version catalog in `gradle/libs.versions.toml` |
| Database | PostgreSQL 16, Spring Data JPA + JDBC for aggregates |
| Migrations | Liquibase |
| API docs | springdoc-openapi 2.8.6 |
| Tests | JUnit 5, MockK, Testcontainers |
| Coverage | JaCoCo, 85% line minimum on unit tests, wired into `check` |

### Spring Boot 4 specifics that differ from 3.x

These were verified against this project's classpath, not assumed:

- `spring-boot-starter-aop` no longer exists. Use `spring-boot-starter-aspectj`.
- `TestRestTemplate` moved to `org.springframework.boot.resttestclient` and needs
  `@AutoConfigureTestRestTemplate`; it also needs `spring-boot-restclient` on the
  classpath. Prefer `MockMvc` or `RestTestClient`.
- `@AutoConfigureMockMvc` is in `org.springframework.boot.webmvc.test.autoconfigure`.
- Per-module test starters exist (`spring-boot-starter-data-jpa-test`,
  `spring-boot-starter-webmvc-test`) and are what test slices should depend on.
- Autoconfiguration classes moved packages (e.g. `org.springframework.boot.jdbc.autoconfigure`).

When something does not resolve, check the actual BOM rather than guessing:
`curl -s https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom`

## Commands

```bash
./gradlew check                 # compile + unit tests + integration tests + coverage gate
./gradlew test                  # unit tests only (fast, no Docker)
./gradlew integrationTest       # integration tests only (requires Docker)
./gradlew jacocoTestReport      # report -> build/reports/jacoco/test/html/index.html
./gradlew bootRun               # run locally (needs a Postgres on DB_URL)

docker compose up --build       # app + Postgres, migrations applied on startup
```

Java 21 is required. If the default JDK is 17:
`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`

## Package layout

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

Each domain module is sliced the same way:

- `domain/model` — entities and value objects as plain Kotlin, using the typed values
  (`Money`, `Quantity`, `BillingPeriod`, …). **No framework annotations at all, JPA
  included.** No knowledge of HTTP or of other modules.
- `domain/port/in` — an inbound port per use case, implemented by the `@Service`.
- `domain/port/out` — outbound ports, exposing only the operations the module performs.
  Never a full `JpaRepository`: a read-only consumer does not get `save` and `deleteAll`.
- `application` — use-case orchestration, transaction boundaries, `@Service`.
- `infrastructure/persistence` — the `@Entity` mirroring the table with primitives, plus
  `toDomain()` and a `fromDomain()` in its companion, and the adapter implementing the
  outbound port. The entity never escapes this package.
- `infrastructure` — other adapters to the outside: JDBC projections, queue adapters.
- `api` — controllers and DTOs. Present only where the module is exposed over HTTP.

**Dependency rule: `api` → `application` → `domain`, and `infrastructure` → `domain`.
The `domain` layer depends on nothing else in the project.** A domain class importing
`org.springframework.web` or another module's `application` package is a defect.

Cross-module communication goes through an interface owned by the *consuming* module
(a port), implemented by the providing module. Modules do not reach into each other's
internals.

## Non-negotiable invariants

Break any of these and the submission fails its own premise:

1. **No double billing.** One `eventId` per tenant produces at most one billable
   rated transaction, even under concurrent delivery. Enforced by
   `UNIQUE (tenant_id, event_id)` and a partial unique index on `rated_transaction`.
2. **No floating point for money.** `BigDecimal` in Kotlin, `NUMERIC` in Postgres.
   A `Double` or `Float` anywhere near an amount is a defect. Rounding is explicit
   (`HALF_UP`), applied once, per transaction — never at total time.
3. **Every amount is explainable.** A rated transaction records the pricing rule id
   *and* a copy of the unit price used, so the amount stays explainable even after
   the rule changes.
4. **History is append-only.** Corrections insert new rows and mark the old ones
   `superseded_by`. Never `UPDATE` a rated amount in place, never delete a raw event.
5. **Tenant isolation is enforced in the database.** The application connects with a
   role that has no `BYPASSRLS`. Application-level checks are the first line of
   defence, never the only one.
6. **Raw event payloads are preserved verbatim.** `raw_event.payload` is the evidence
   a reviewer traces back to.

## Engineering standards

Applied to production code in this repo. See `.claude/skills/` for the detail.

**SOLID** — in Kotlin terms, not textbook terms:
- Single responsibility: a rating service rates. It does not also persist, publish
  events and format HTTP responses.
- Open/closed: new pricing behaviour arrives as a new strategy, not as another
  `when` branch in an existing calculator.
- Liskov: no subtype that throws on a method its supertype defines.
- Interface segregation: narrow ports. A consumer that only reads gets a read port.
- Dependency inversion: `domain` defines the interface, `infrastructure` implements it.
  Constructor injection only — no `@Autowired` on fields, no service locator.

**Clean architecture** — the dependency rule above is the whole of it. Framework
concerns stay at the edges; the domain is testable with plain JUnit and no context.

**Kotlin idioms:**
- Prefer `data class` for value objects, `value class` for typed identifiers.
- Model absence with nullable types, not sentinels. Model failure with sealed
  result types where the caller must handle it; exceptions for genuinely exceptional cases.
- No `!!` in production code. No `lateinit` outside test fixtures.
- Immutability by default: `val`, read-only collections, no mutable shared state.

**Testing:**
- Test behaviour and invariants, not method calls. A test that only asserts a mock
  was invoked proves nothing and inflates coverage without adding confidence.
- Unit tests carry no Spring context. Integration tests are tagged `integration`
  and use Testcontainers.
- Every boundary condition in rating gets an explicit test, especially
  `effective_from` inclusive / `effective_to` exclusive.

## Conventions

- Timestamps are `Instant`, always UTC, `TIMESTAMPTZ` in the database.
- `occurredAt` drives the billing period and the pricing rule lookup.
  `receivedAt` drives late-arrival detection. They are independent; both persist.
- Money: `BigDecimal`, scale 4 for amounts, scale 6 for unit prices and quantities.
- Errors: RFC 7807 `application/problem+json`. Never leak a stack trace.
- Migrations are immutable once applied. Correct a mistake with a new changeset.
- Configuration comes from environment variables with development-only defaults.
  No credential is ever committed.

## What to do when unsure

State the assumption explicitly in the README and proceed, rather than silently
picking one. The exercise is evaluated partly on explicit assumptions and clear
communication. If a requirement is genuinely ambiguous, note it as an open question.
