---
name: clean-architecture
description: Apply this project's clean-architecture and SOLID rules when writing or refactoring Kotlin production code — layering, the dependency rule, ports and adapters, constructor injection, and the Kotlin idioms that follow from them. Use before adding a class, a service, a repository, or a cross-module call, and when reviewing whether code sits in the right layer.
---

# Clean architecture and SOLID in this codebase

## The dependency rule

```
api ──→ application ──→ domain ←── infrastructure
```

`domain` depends on nothing else in the project. Everything points inward.

Concretely, inside `domain`:

- No `org.springframework.web.*`, no `jakarta.servlet.*`.
- No import from another module's `application`, `api` or `infrastructure`.
- JPA mapping annotations are tolerated (pragmatic choice, documented in the README);
  everything else framework-shaped is not.

A quick check before committing:

```bash
grep -rn "org.springframework.web\|jakarta.servlet" src/main/kotlin/**/domain/ && echo "LAYER VIOLATION"
```

## Where a class belongs

| It... | ...belongs in |
| --- | --- |
| holds a business rule or invariant | `domain` |
| is an entity, value object or typed id | `domain` |
| declares what the module needs from outside (a port) | `domain` |
| orchestrates a use case and opens a transaction | `application` |
| implements a port using JPA, JDBC or HTTP | `infrastructure` |
| maps HTTP to a use case | `api` |

The most common mistake is business logic leaking into `application` because it was
easier to write it inline in the service. If a rule can be stated without mentioning
transactions, persistence or HTTP, it belongs in `domain`.

## Ports and adapters

The port is an interface in the module that *needs* the capability, named for what
the consumer wants, not for how it is implemented:

```kotlin
// pricing/domain/PricingRuleLookup.kt  -- owned by the consumer's domain
interface PricingRuleLookup {
    fun findApplicable(tenant: TenantId, code: TransactionCode, at: Instant): PricingRule?
}
```

```kotlin
// pricing/infrastructure/JpaPricingRuleLookup.kt  -- the adapter
@Repository
class JpaPricingRuleLookup(private val jpa: PricingRuleJpaRepository) : PricingRuleLookup {
    override fun findApplicable(...) = ...
}
```

Modules never import each other's `infrastructure`. If `rating` needs pricing, it
depends on an interface, and Spring wires the implementation.

## SOLID, applied

**Single responsibility.** A class has one reason to change. `RatingService` computes
a rated result; it does not also persist it, mark the outbox, and build a response
body. When a service grows a second "and", split it.

**Open/closed.** New pricing behaviour (tiers, minimums, discounts) arrives as a new
implementation of a strategy interface, not as another branch in a `when` inside
`AmountCalculator`. The existing calculator should not need reopening.

**Liskov.** No implementation throws `UnsupportedOperationException` for a method its
interface declares. If an implementation cannot honour the contract, the interface is
wrong — segregate it.

**Interface segregation.** A consumer that only reads pricing gets a read-only port.
Do not hand a write-capable repository to code that has no business writing.

**Dependency inversion.** Constructor injection, always:

```kotlin
// Good -- dependencies are explicit, the class is trivially unit-testable
@Service
class RateTransactionUseCase(
    private val pricingRules: PricingRuleLookup,
    private val ratedTransactions: RatedTransactionRepository,
    private val clock: Clock,
)
```

Never `@Autowired` on a field, never a service locator, never `ApplicationContext`
injected to look beans up by hand. Inject `Clock`, never call `Instant.now()`
directly in code with behaviour worth testing.

## Kotlin idioms that follow

**Typed identifiers** — a `String` tenant id and a `String` customer id are
interchangeable to the compiler and that is exactly the bug class we want gone:

```kotlin
@JvmInline value class TenantId(val value: String) {
    init { require(value.isNotBlank()) { "tenantId must not be blank" } }
}
```

**Model results the caller must handle:**

```kotlin
sealed interface IngestResult {
    data class Accepted(val eventId: EventId, val receivedAt: Instant) : IngestResult
    data class Duplicate(val eventId: EventId, val originalReceivedAt: Instant) : IngestResult
    data class Rejected(val reasons: List<String>) : IngestResult
}
```

A `when` over a sealed type is exhaustive: adding a new outcome breaks compilation at
every call site that must consider it. That is the point.

**Immutability by default.** `val` over `var`. `List` over `MutableList` in signatures.
Domain objects do not expose mutable state.

**Banned in production code:** `!!`, `lateinit` (outside test fixtures), mutable
top-level state, `Double`/`Float` anywhere near money, `Instant.now()` inside logic
that a test needs to control.

## Before you finish

- Does `domain` still compile without any other module? (Conceptually — check imports.)
- Is every dependency injected through the constructor?
- Does each new class have one reason to change?
- Could this class be unit-tested with no Spring context? If not, why not?
