---
name: spring-testing
description: How to write tests in this project — choosing a test slice, Testcontainers setup, MockK usage, and the specific tests that must exist to prove AOP application, transaction rollback, idempotency and tenant isolation. Use when adding any test, or when deciding which Spring test annotation a scenario needs.
---

# Testing in this project

The brief distinguishes tests that prove behaviour from tests that merely exercise
code for coverage. Write the first kind.

## Choosing the slice

Pick the narrowest thing that can prove the claim:

| Proving... | Use | Cost |
| --- | --- | --- |
| a calculation, a rule, a boundary | plain JUnit + MockK, no Spring | milliseconds |
| a constraint, a query, a migration | `@DataJpaTest` + Testcontainers | seconds |
| a controller contract, status codes | `@WebMvcTest` | ~1s |
| AOP, transactions, the outbox, RLS | `@SpringBootTest` + Testcontainers | slowest |

Reaching for `@SpringBootTest` when a unit test would do is the most common way to
make a suite slow and vague at the same time.

## Tagging

Anything needing Docker is tagged, so `./gradlew test` stays fast and the coverage
gate measures unit tests only:

```kotlin
@Tag("integration")
@SpringBootTest
class OutboxWorkerIntegrationTest { ... }
```

`./gradlew test` excludes the tag; `./gradlew integrationTest` runs exactly it;
`./gradlew check` runs both plus the coverage gate.

## Testcontainers

One shared container per suite, injected by `@ServiceConnection` — no manual
datasource property wiring:

```kotlin
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestContainer {
    companion object {
        @Bean @ServiceConnection
        fun postgres(): PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
    }
}
```

Liquibase runs against the container, so the migrations themselves are under test,
not just the code. A migration that breaks is caught here, not in review.

## Spring Boot 4 notes

Verified against this classpath — these moved from 3.x:

- `@AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure`
- `TestRestTemplate` → `org.springframework.boot.resttestclient`, requires
  `@AutoConfigureTestRestTemplate` **and** `spring-boot-restclient` on the classpath.
  Prefer `MockMvc`; reach for `RestTestClient` when a real port is genuinely needed.
- Per-slice test starters exist: `spring-boot-starter-data-jpa-test`,
  `spring-boot-starter-webmvc-test`.

## MockK

`springmockk` replaces `@MockBean`, which does not understand Kotlin finals:

```kotlin
@MockkBean lateinit var pricingRules: PricingRuleLookup

every { pricingRules.findApplicable(any(), any(), any()) } returns rule
```

Use `relaxed = true` sparingly — a relaxed mock silently returns defaults, which hides
the case where the code called something you did not expect.

## The four tests the brief explicitly requires

### 1. AOP is applied — including its limitation

```kotlin
// The aspect fires through the proxy
assertFailsWith<MissingTenantException> {
    service.rateTransaction(eventId)          // no tenant in context
}

// Self-invocation bypasses the proxy: the aspect does NOT fire.
// This is a real Spring AOP limitation and we prove it rather than claim it —
// and then prove the database catches what the aspect missed.
service.rateViaInternalSelfCall(eventId)      // no exception from the aspect
assertEquals(0, repository.findAllVisible().size)   // RLS returned nothing
```

That second half is the argument for defence in depth. A test that only shows the
happy path does not make it.

### 2. Transaction rollback

```kotlin
// A failure after the insert leaves nothing behind
assertFailsWith<RatingFailedException> { useCase.ingestAndFail(event) }
assertNull(rawEventRepository.findByEventId(event.eventId))

// But the rejection record, written with REQUIRES_NEW, survives the rollback
assertNotNull(rejectedEventRepository.findByEventId(event.eventId))
```

### 3. Idempotency under real concurrency

Sequential calls do not prove anything about a race. Use actual threads:

```kotlin
val latch = CountDownLatch(1)
val results = (1..10).map {
    executor.submit { latch.await(); ingest(sameEvent) }
}
latch.countDown()                                   // release all at once

assertEquals(1, results.count { it.get() is Accepted })
assertEquals(9, results.count { it.get() is Duplicate })
assertEquals(1, ratedTransactionRepository.countByEventId(sameEvent.eventId))
```

### 4. Tenant isolation, at both layers

```kotlin
// Seed tenant-a and tenant-b, then read as tenant-a
TenantContext.runAs(TenantId("tenant-a")) {
    assertTrue(service.findAll().all { it.tenantId.value == "tenant-a" })
}

// And prove the database refuses even a deliberately unfiltered query
TenantContext.runAs(TenantId("tenant-a")) {
    val all = jdbc.queryForList("SELECT * FROM rated_transaction")  // no WHERE
    assertTrue(all.none { it["tenant_id"] == "tenant-b" })          // RLS did it
}
```

## Signs a test is not earning its place

- Its only assertion is `verify { mock.save(any()) }`.
- It asserts on a value it just stubbed.
- It would still pass if the method body were replaced with the identity function.
- It calls the same method twice sequentially and is named `...concurrent...`.
- It exists because coverage was at 84%.

If a test cannot fail for a reason you can name, delete it.
