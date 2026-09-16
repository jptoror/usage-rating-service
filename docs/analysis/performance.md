# Performance

Measured, not estimated. Every figure here comes from `scripts/load-test.sh` against the
assembled service in Docker Compose.

**These numbers are floors, not ratings.** The application instances and PostgreSQL share
one Docker VM on a laptop — 14 CPUs, 8 GB — so they compete for the same cores. What the
measurements establish is the *order of magnitude* and, more usefully, the *shape* of the
scaling curve.

---

## Results

500 events unless noted; 3,000 for the contention runs. Concurrency 25 and 50 respectively.

| # | Instances | Poll | Batch | Events | Ingest (ev/s) | Rate (ev/s) | Drain | Work split |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | 1000 ms | 50 | 500 | 299 | **36** | 12.2s | — |
| 2 | 3 | 200 ms | 50 | 500 | 259 | **107** | 2.7s | 1 instance took all 500 |
| 3 | 1 | 200 ms | 50 | 3,000 | 339 | **140** | 12.5s | — |
| 4 | 3 | 200 ms | 50 | 3,000 | 295 | **255** | 1.6s | 1105 / 951 / 944 |
| 5 | **1** | **200 ms** | **200** | 3,000 | 329 | **228** | 4.0s | — |
| 6 | **3** | **200 ms** | **200** | 3,000 | 263 | **255** | 2.3s | 1067 / 1031 / 902 |

Runs 5 and 6 use the defaults this service now ships. Ingestion latency on run 5:
**p50 5 ms · p95 10 ms · p99 196 ms · max 302 ms**.

Every run: all events accepted, all rated, **zero double-billed**.

### Two headlines

**Configuration beat hardware.** One instance tuned (run 5, 228 ev/s) is within 10% of
three instances untuned (run 4, 255 ev/s) — at a third of the compute.

**And then scaling stopped working.** Three tuned instances (run 6) reach 255 ev/s, which
is the same 255 ev/s that three *untuned* instances reached, and only 12% above one tuned
instance. Ingestion throughput fell as well, 329 → 263 ev/s.

That is the signature of a shared bottleneck: the workers are no longer the constraint,
PostgreSQL is. Adding a fourth instance would not help, and the honest conclusion is that
**this system scales with instances only until the database saturates — which, on this
hardware, is at roughly 250 events/s.** Past that the next move is database capacity, not
application replicas.

Without run 6 the tempting claim would have been "it scales linearly with instances".
The measurement says it scales until it does not, and names where.

---

## What the numbers say

### The estimate in the complexity analysis was wrong, and wrong in an instructive way

It said a worker "sustains on the order of a few hundred messages per second", reasoning
from round trips. The measurement says **36**.

The reasoning was not arithmetically wrong — it was answering the wrong question. The
worker is not limited by how fast it can process a message. It is limited by **how often
it asks for work**:

```
batch size 50 ÷ poll interval 1 s = 50 events/s ceiling
```

Measured 36/s, against a design ceiling of 50/s. The workers were idle, not saturated.
No amount of database tuning or JVM work would have moved this number; the constraint
was a configuration value.

That is the general lesson: a throughput estimate derived from per-unit cost silently
assumes the system is always working. This one spends most of its time asleep.

### Poll interval buys more than instances do — at first

Isolating the two variables (runs 1, 3, 4):

| Change | Instances | Rating throughput | Gain |
| --- | --- | --- | --- |
| Baseline: 1000 ms, batch 50 | 1 | 36 ev/s | — |
| **Poll 1000 ms → 200 ms** | 1 | 140 ev/s | **3.9×** |
| **Batch 50 → 200** | 1 | 228 ev/s | **1.6×** |
| Add two instances, tuned | 3 | 255 ev/s | **1.1×** |

Two free configuration changes took one instance from 36 to 228 ev/s — **6.3×**.
Tripling the hardware on top of that bought **12%**.

The order matters, and the measurement establishes it: tune the cadence, then the batch,
then — only if the database still has headroom — add instances. Scaling out first would
have tripled the infrastructure bill to work around two configuration values, and then
hit the same ceiling anyway.

### `SKIP LOCKED` distributes work — once there is work to contend for

Run 2 is the interesting failure. Three instances, and **one of them processed all 500
events**:

```
0f8bd73b532d: 500
```

That is not a bug, and it is worth being precise about why. With a 200 ms poll and a
batch of 50, the first instance to wake drains a 500-event queue in ten batches — about
two seconds — while the others are still sleeping between polls. There was never
contention to resolve.

Run 4, with 3,000 events, creates real contention, and the split is near-perfect:

```
0f8bd73b532d: 1105
01b6c48a2435:  951
c359160df7c9:  944
```

**Both runs are `SKIP LOCKED` behaving correctly.** It does not distribute work evenly;
it guarantees that concurrent claimants take *disjoint* batches. Even distribution is a
consequence of sustained contention, not a property of the mechanism.

This matters for how the claim is stated. "Three instances share the load" is true under
load and misleading at rest — and a reviewer who tested it with a small burst would see
one instance doing everything and reasonably conclude the coordination was broken.

### Ingestion is flat, and that is the right shape

299 → 259 → 339 → 295 ev/s across every configuration: ingestion does not improve with
more instances here, because the bottleneck is PostgreSQL's write path, which all
instances share. Three application containers contending for one database's write
throughput is not three times the write throughput.

It is also comfortably ahead of rating in every run, which is the correct relationship:
the queue absorbs the difference, which is what a queue is for. Ingestion stays fast for
the caller while rating catches up.

The p99 of 119 ms against a p50 of 7 ms is the ordinary shape of a connection pool under
burst — a few requests wait for a connection. At 50 concurrent senders against a pool of
15, that is expected rather than alarming.

---

## A measurement that lied, briefly

Worth recording, because it is the kind of mistake that makes a benchmark worse than
no benchmark.

After changing the application defaults to 200 ms / 200, the next run reported the same
rating throughput as before. The defaults had not taken effect: `docker-compose.yml`
passed `OUTBOX_POLL_INTERVAL=1000ms` explicitly, and **an environment variable wins over
the YAML default**. The run measured the old configuration while appearing to measure
the new one.

Two things came out of it:

- The compose defaults and `application.yml` now carry the same values, with a comment
  in each saying they must stay aligned.
- `load-test.sh` reads the poll interval and batch size **out of the running container**
  and prints them in the report. A performance report that does not state the
  configuration it measured is not comparable to anything, including itself.

---

## What would be done differently in production

1. **Already done:** the defaults now ship at 200 ms and 200 per batch, measured at
   228 ev/s on one instance against 36 ev/s before.
2. **Watch the ingestion ceiling next.** At 329 ev/s ingested against 228 ev/s rated, the
   two are now within the same order of magnitude — so the next bottleneck is the shared
   PostgreSQL write path, not the worker. That is a database sizing question rather than
   an application one.
3. **Alert on queue depth, not on throughput.** Throughput looks healthy right up until
   the queue is growing faster than it drains. `outbox_message` depth over time is the
   metric that catches it.
4. **Measure again on production-shaped hardware.** Everything here shares one VM. A real
   deployment with a dedicated database would shift the ingestion ceiling substantially
   and change which component is the constraint.

---

## Reproducing

```bash
./scripts/start.sh                              # 1 instance, 1000 ms default
./scripts/load-test.sh --events 500

OUTBOX_POLL_INTERVAL=200ms docker compose up -d --build   # 1 instance, faster polling
./scripts/load-test.sh --events 3000 --concurrency 50

./scripts/start.sh --scale 3                    # 3 instances, 200 ms
./scripts/load-test.sh --events 3000 --concurrency 50 --evidence
```

The script asserts correctness as well as timing, so a run that is fast but double-bills
fails rather than reporting an impressive number.
