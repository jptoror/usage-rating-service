---
name: pr-review
description: Review changes in this usage-rating service for correctness, financial safety, tenant isolation, architecture conformance and test quality. Use when reviewing a diff, a branch, a pull request, or before committing a batch of work. Produces findings ordered by severity with file:line references.
---

# Reviewing changes in this repository

Review in the order below. It runs cheapest-to-most-expensive, and the early
categories are the ones that sink this submission if they are wrong.

## 0. Get the diff

```bash
git diff main...HEAD --stat
git diff main...HEAD
```

For uncommitted work: `git diff HEAD`.

## 1. Financial correctness — blocking

- [ ] No `Double`, `Float`, or `toDouble()` anywhere in a monetary path.
      `grep -rn "Double\|Float\|toDouble" src/main/kotlin/ | grep -iv "doubleQuote"`
- [ ] Every `BigDecimal` division specifies scale **and** rounding mode.
      An unspecified division throws `ArithmeticException` on a non-terminating result —
      that is a production incident waiting for the right input.
- [ ] Rounding happens **once**, per transaction, when persisting the amount.
      Never round an intermediate, never round the total of already-rounded lines.
- [ ] `BigDecimal` comparisons use `compareTo`, not `==`.
      `2.0` and `2.00` are unequal under `equals` and equal under `compareTo`.
- [ ] A rated transaction stores both `pricing_rule_id` and a copy of `unit_price`.
- [ ] Corrections insert new rows; nothing `UPDATE`s an amount in place.

## 2. Idempotency and concurrency — blocking

- [ ] Duplicate detection relies on a database constraint, not a `SELECT`-then-`INSERT`.
      A read-then-write has a race window that concurrent delivery will find.
- [ ] Every writer downstream of ingestion is itself idempotent. At-least-once
      delivery means the worker *will* process the same message twice.
- [ ] `@Transactional` is not on a method that also does external I/O it cannot roll back.
- [ ] Post-commit work uses `@TransactionalEventListener(AFTER_COMMIT)` or the outbox,
      never a bare `@Async` inside a transactional method — the async work can start
      before the commit lands and fail to see the row.

## 3. Tenant isolation — blocking

- [ ] No query filters by a tenant id taken from the request **body**. The tenant
      comes from `TenantContext`, populated from the header.
- [ ] Every repository method that reads tenant-scoped data takes a tenant parameter
      or is covered by an RLS policy. Preferably both.
- [ ] New tenant-scoped tables have RLS enabled and a policy, in the same migration
      that creates them.
- [ ] Asynchronous work re-establishes the tenant context explicitly. A `ThreadLocal`
      does not cross a thread pool boundary.
- [ ] `@RequiresTenant` is not invoked through `this.method()` from inside the same
      class — self-invocation bypasses the proxy and the aspect never runs.

## 4. Architecture conformance

- [ ] The dependency rule holds: `domain` imports nothing from `api`, `application`,
      `infrastructure`, or another module.
- [ ] Business rules live in `domain`, not inlined in an `application` service.
- [ ] Cross-module calls go through a port interface, not a direct class reference.
- [ ] Constructor injection everywhere; no field `@Autowired`.
- [ ] No `!!`; no `lateinit` outside test fixtures.

See the `clean-architecture` skill for the full rules.

## 5. Persistence and migrations

- [ ] Invariants are enforced by constraints, not only by application code.
      Ask: "if two instances did this simultaneously, would the database stop it?"
- [ ] New migrations are new changesets. No edit to an already-applied changeset —
      Liquibase checksums will reject it and the deployment fails.
- [ ] Indexes exist for the query patterns actually introduced.
- [ ] Monetary columns are `NUMERIC` with explicit precision, never `float8`.
- [ ] `TIMESTAMPTZ`, never `TIMESTAMP`.

## 6. Test quality

The brief evaluates "tests that prove important behavior rather than merely exercise
code". Judge accordingly:

- [ ] Does each new test assert an outcome, or only that a mock was called?
      A test whose only assertion is `verify { repo.save(any()) }` proves nothing.
- [ ] Are the rating boundary cases covered? Specifically `occurredAt == effective_from`
      (applies) and `occurredAt == effective_to` (does not apply).
- [ ] Do the integration tests actually exercise concurrency, or just call twice
      sequentially and call it a race test?
- [ ] Are unit tests free of Spring context?
- [ ] Is new production code inside the coverage gate? Only bootstrap and pure
      `@Configuration` wiring may be excluded, and each exclusion is documented.

## 7. Operational

- [ ] No credential, connection string or secret in committed files.
- [ ] New configuration is environment-overridable with a development default.
- [ ] Logs carry tenant and event identifiers, and no payload content that could
      be sensitive.
- [ ] A new long-running loop honours shutdown — it must stop claiming work on SIGTERM.

## Reporting

Order findings by severity and give each a `file:line`. Separate what blocks a merge
from what is a preference:

```
BLOCKING  src/main/kotlin/.../AmountCalculator.kt:42
  divide() without a rounding mode throws on non-terminating decimals.
  Fails for unitPrice=10, quantity=3.

NON-BLOCKING  src/main/kotlin/.../IngestionService.kt:88
  This 40-line method would read better split around the validation step.
```

Do not pad a review with style notes when there is a correctness finding. State
plainly when the diff is clean — a review with no findings is a valid outcome.
