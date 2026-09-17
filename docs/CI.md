# Continuous integration

Every pull request runs the full set of quality gates. The jobs are split so a failure
names what broke, rather than reporting "the build failed" for six different problems.

| Job | Gate | Fails when |
| --- | --- | --- |
| Compile | main and test sources build | a compile error, in ~1 minute without starting a container |
| Unit tests and coverage | `./gradlew test` + 85% line coverage | a unit test fails, or coverage drops below the gate |
| Integration tests | `./gradlew integrationTest` | Testcontainers-backed behaviour breaks, including a bad migration |
| Architecture and invariants | `DependencyRuleTest` + banned-construct greps | a module imports another's infrastructure, or `!!` / floating point / unrounded `divide` appears |
| Docker and end-to-end | image builds, stack starts, scripts pass | the assembled service misbehaves over the wire |
| Multi-instance | three instances under contention | `SKIP LOCKED` stops coordinating, or an event is billed twice |
| **All quality gates** | aggregate | any of the above fails |

Ordering is cheapest-first: `Compile` catches a syntax error without ever starting Docker.

## Branch protection

Require the single **`All quality gates`** check. It aggregates the others, so adding a
job does not mean remembering to update the protection rules — the rule stays correct by
construction.

## Why the checks are separate from the test suite

The unit and integration suites are thorough, but three classes of defect reached the
finished service anyway and were caught only by running it:

- A Kotlin value class as an injected constructor parameter compiled, unit-tested green,
  and stopped the container from starting.
- A malformed query parameter returned 500 instead of 400.
- An integration test passed alone and failed in the suite.

The Docker and multi-instance jobs exist for that gap. They run the same scripts a
developer runs locally, which assert and exit non-zero, so there is one definition of
"passing" rather than two.

## Running the gates locally

```bash
./gradlew check                     # compile, unit, integration, coverage
./scripts/start.sh && ./scripts/e2e-test.sh && ./scripts/edge-case-test.sh
./scripts/start.sh --scale 3 && ./scripts/multi-instance-test.sh
```
