# Documentation

| Document | What it covers |
| --- | --- |
| [Architecture](diagrams/architecture.md) | System context, module dependencies, data model, database-enforced invariants, deployment |
| [Sequence diagrams](diagrams/sequences.md) | One per operation, with transaction boundaries marked |
| [Manual testing](manual-testing.md) | Walking through the service by hand, and what each scenario proves |
| [Complexity analysis](analysis/complexity.md) | Big-O per operation, where the limits are, what would break first |
| [Performance](analysis/performance.md) | Measured throughput and latency, and what the measurements corrected |
| [Evidence](evidence/) | Transcripts from actual runs — requests, responses, and per-instance breakdowns |

The [README](../README.md) at the repository root covers build, run, configuration and
the design decisions themselves.

---

## Scripts

All under `scripts/`. Each asserts and exits non-zero on failure, so they work in CI as
well as by hand.

| Script | Purpose |
| --- | --- |
| `start.sh [--scale N]` | Start the service; `--scale 3` runs three instances behind nginx |
| `stop.sh [--clean]` | Stop; `--clean` also deletes the database volume |
| `e2e-test.sh [--evidence]` | 22 checks across all eight functional requirements |
| `edge-case-test.sh [--evidence]` | Boundaries through the full stack — precision, validation, isolation |
| `multi-instance-test.sh [--evidence]` | Coordination across separate processes under real contention |
| `load-test.sh [--events N] [--evidence]` | Throughput, latency percentiles, and whether instances actually help |

`--evidence` writes a timestamped transcript to `evidence/`.

```bash
./scripts/start.sh --scale 3
./scripts/e2e-test.sh --evidence
./scripts/edge-case-test.sh --evidence
./scripts/multi-instance-test.sh --evidence
./scripts/load-test.sh --events 3000 --concurrency 50 --evidence
./scripts/stop.sh --clean
```

---

## Why the scripts exist alongside the test suite

The automated suite (353 tests, 91.64% line coverage) runs against Testcontainers and
covers the domain thoroughly. The scripts cover what it structurally cannot:

- **The wire.** A scale lost in JSON, a timezone applied by a driver, a numeric column
  truncating. A seeded price changeover was five hours off in exactly this way, and no
  unit test could have seen it.
- **Separate processes.** The suite proves concurrency between threads sharing one
  connection pool. Three containers with three pools and three schedulers is a different
  claim, and it is the one the brief asks about.
- **The assembled application.** A Kotlin constructor default that breaks Spring bean
  creation compiles, unit-tests green, and fails only when the container starts. That
  happened; `ApplicationContextIntegrationTest` now catches it.

Several defects reached the finished service and were found only by running it — and two
more needed a fresh clone rather than a working tree, so not even running it locally
would have shown them. All are listed in the root README under "Notes from building
this."

Measuring rather than estimating corrected a fourth thing: the complexity analysis had
claimed a worker sustains "a few hundred messages per second", reasoning from per-message
cost. It sustains 36, because the limit is `batchSize / pollInterval` and the worker was
idle most of the time. The defaults changed as a result.
