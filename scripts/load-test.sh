#!/usr/bin/env bash
#
# Measures throughput and latency, and whether adding instances actually helps.
#
#   ./scripts/start.sh --scale 3
#   ./scripts/load-test.sh
#   ./scripts/load-test.sh --events 2000 --concurrency 50 --evidence
#
# This is a different question from `multi-instance-test.sh`, which proves CORRECTNESS
# under contention — no double billing, disjoint batches. Correct and fast are separate
# claims, and this one measures:
#
#   - ingestion throughput (events accepted per second) and latency percentiles
#   - rating throughput (events drained from the outbox per second)
#   - whether 3 instances beat 1, which is the only way "it scales" is worth saying
#
# It is a load test, not a benchmark: it runs on a laptop against containers sharing one
# Docker VM, so the absolute numbers are a floor, not a rating. What it does establish is
# the ORDER OF MAGNITUDE and the SHAPE of the scaling curve, which is what the complexity
# analysis needs to stop guessing.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh

EVENTS=500
CONCURRENCY=25
EVIDENCE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --events)      EVENTS="$2"; shift 2 ;;
        --concurrency) CONCURRENCY="$2"; shift 2 ;;
        --evidence)    EVIDENCE="docs/evidence/load-$(date -u +%Y%m%dT%H%M%SZ).md"; shift ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; exit 1 ;;
    esac
done

PERIOD=$(current_period)
RECENT=$(days_ago 1)
export CUSTOMER="load-$(date -u +%H%M%S)-$$"

psql_query() {
    docker compose exec -T postgres psql -U usage_owner -d usage_rating -tAc "$1" 2>/dev/null | grep -v '^SET$'
}

wait_for_health
INSTANCES=$(docker compose ps --format '{{.Service}}' 2>/dev/null | grep -c '^app$' || echo 1)

# Read from the running container rather than assumed: the poll interval bounds rating
# throughput more than anything else, so a report without it is not comparable to
# another run.
POLL_INTERVAL=$(docker compose exec -T app printenv OUTBOX_POLL_INTERVAL 2>/dev/null | tr -d '\r' || echo "default")
BATCH_SIZE=$(docker compose exec -T app printenv OUTBOX_BATCH_SIZE 2>/dev/null | tr -d '\r' || echo "default")

section "Load profile"
info "Instances:   $INSTANCES"
info "Poll:        ${POLL_INTERVAL:-default}, batch ${BATCH_SIZE:-default}"
info "Events:      $EVENTS"
info "Concurrency: $CONCURRENCY parallel senders"
info "Customer:    $CUSTOMER"

# ---------------------------------------------------------------------------
# Ingestion: throughput and latency
# ---------------------------------------------------------------------------

section "Ingestion"

TIMING_FILE=$(mktemp)
trap 'rm -f "$TIMING_FILE"' EXIT

# curl reports its own total time per request, which is the honest measure: it includes
# connection setup and the full round trip, not just the server's view.
INGEST_START=$(python3 -c 'import time; print(time.time())')

seq 1 "$EVENTS" | xargs -P "$CONCURRENCY" -I{} bash -c "
    source scripts/lib.sh
    export CUSTOMER='$CUSTOMER'
    curl -sS -m 30 -o /dev/null -w '%{http_code} %{time_total}\n' \
        -X POST '$BASE_URL/api/v1/transactions' \
        -H 'X-Tenant-Id: $TENANT' -H 'Content-Type: application/json' \
        -d \"{
              \\\"eventId\\\": \\\"\$(uuid)\\\",
              \\\"customerId\\\": \\\"$CUSTOMER\\\",
              \\\"transactionCode\\\": \\\"VEHICLE_REGISTRATION\\\",
              \\\"occurredAt\\\": \\\"$RECENT\\\",
              \\\"metadata\\\": {\\\"quantity\\\": 2}
            }\" >> '$TIMING_FILE'
" 2>/dev/null

INGEST_END=$(python3 -c 'import time; print(time.time())')

INGEST_STATS=$(python3 - "$TIMING_FILE" "$INGEST_START" "$INGEST_END" "$EVENTS" <<'PY'
import sys

path, start, end, expected = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), int(sys.argv[4])
latencies, accepted, other = [], 0, 0

for line in open(path):
    parts = line.split()
    if len(parts) != 2:
        continue
    status, seconds = parts
    if status == '202':
        accepted += 1
    else:
        other += 1
    latencies.append(float(seconds) * 1000)

latencies.sort()
def pct(p):
    if not latencies:
        return 0.0
    return latencies[min(int(len(latencies) * p / 100), len(latencies) - 1)]

elapsed = end - start
print(f"{accepted}|{other}|{elapsed:.2f}|{accepted / elapsed if elapsed else 0:.1f}"
      f"|{pct(50):.0f}|{pct(95):.0f}|{pct(99):.0f}|{latencies[-1] if latencies else 0:.0f}")
PY
)

IFS='|' read -r ACCEPTED OTHER INGEST_ELAPSED INGEST_RPS P50 P95 P99 PMAX <<< "$INGEST_STATS"

info "Accepted:    $ACCEPTED / $EVENTS  (other statuses: $OTHER)"
info "Elapsed:     ${INGEST_ELAPSED}s"
info "Throughput:  ${INGEST_RPS} events/s"
info "Latency:     p50 ${P50}ms · p95 ${P95}ms · p99 ${P99}ms · max ${PMAX}ms"

# ---------------------------------------------------------------------------
# Rating: how fast the workers drain what was just queued
# ---------------------------------------------------------------------------

section "Rating"

# Measured from the moment ingestion finished. Some rating overlaps with ingestion, so
# this is a conservative floor for drain throughput, not a peak figure.
DRAIN_START=$(python3 -c 'import time; print(time.time())')

for _ in $(seq 1 300); do
    REMAINING=$(psql_query "
        SET app.tenant_id = '$TENANT';
        SELECT count(*) FROM outbox_message o
        JOIN raw_event e ON e.id = o.raw_event_id
        WHERE e.customer_id = '$CUSTOMER' AND o.status NOT IN ('DONE','UNRATED','QUARANTINED','FAILED');
    " | tail -1)
    [ "${REMAINING:-1}" = "0" ] && break
    sleep 0.5
done

DRAIN_END=$(python3 -c 'import time; print(time.time())')
DRAIN_ELAPSED=$(python3 -c "print(f'{$DRAIN_END - $DRAIN_START:.2f}')")

RATED=$(psql_query "
    SET app.tenant_id = '$TENANT';
    SELECT count(*) FROM rated_transaction WHERE customer_id = '$CUSTOMER' AND superseded_by IS NULL;
" | tail -1)

# Total wall clock from the first request to the last rated row: the figure that matters
# for "how long until the data is billable".
TOTAL_ELAPSED=$(python3 -c "print(f'{$DRAIN_END - $INGEST_START:.2f}')")
RATING_RPS=$(python3 -c "print(f'{$RATED / max($TOTAL_ELAPSED, 0.01):.1f}')")

info "Rated:       $RATED / $ACCEPTED"
info "Drain time:  ${DRAIN_ELAPSED}s after ingestion finished"
info "End to end:  ${TOTAL_ELAPSED}s from first request to last rated row"
info "Throughput:  ${RATING_RPS} events/s rated"

# ---------------------------------------------------------------------------
# Correctness is not optional under load
# ---------------------------------------------------------------------------

section "Correctness under load"

DOUBLE_BILLED=$(psql_query "
    SET app.tenant_id = '$TENANT';
    SELECT count(*) FROM (
        SELECT raw_event_id FROM rated_transaction
        WHERE customer_id = '$CUSTOMER' AND superseded_by IS NULL
        GROUP BY raw_event_id HAVING count(*) > 1
    ) d;
" | tail -1)

FAILURES=0
[ "$ACCEPTED" = "$EVENTS" ] && pass "every event accepted" || { fail "only $ACCEPTED of $EVENTS accepted"; FAILURES=1; }
[ "$RATED" = "$ACCEPTED" ]  && pass "every accepted event rated" || { fail "$RATED rated of $ACCEPTED"; FAILURES=1; }
[ "${DOUBLE_BILLED:-1}" = "0" ] && pass "no event billed twice" || { fail "$DOUBLE_BILLED double-billed"; FAILURES=1; }

DISTRIBUTION=$(psql_query "
    SET app.tenant_id = '$TENANT';
    SELECT o.processed_by || ': ' || count(*)
    FROM outbox_message o JOIN raw_event e ON e.id = o.raw_event_id
    WHERE e.customer_id = '$CUSTOMER' AND o.processed_by IS NOT NULL
    GROUP BY o.processed_by ORDER BY count(*) DESC;
" | grep ':' || true)

printf '%s\n' "$DISTRIBUTION" | while read -r line; do [ -n "$line" ] && info "$line"; done

# ---------------------------------------------------------------------------
section "Result"
# ---------------------------------------------------------------------------

info "$INSTANCES instance(s): ${INGEST_RPS} events/s ingested, ${RATING_RPS} events/s rated end to end"

if [ -n "$EVIDENCE" ]; then
    mkdir -p docs/evidence
    cat > "$EVIDENCE" <<REPORT
# Load test

Generated by \`scripts/load-test.sh\` on $(date -u +%Y-%m-%dT%H:%M:%SZ).

Measured on a developer laptop, with the application and PostgreSQL sharing one Docker
VM. **These are floors, not ratings** — the database and every application instance
compete for the same cores. What the numbers establish is the order of magnitude and the
shape of the scaling curve, which is what the complexity analysis needs in order to stop
estimating.

## Configuration

| | |
| --- | --- |
| Instances | $INSTANCES |
| Poll interval | ${POLL_INTERVAL:-default} |
| Batch size | ${BATCH_SIZE:-default} |
| Events | $EVENTS |
| Parallel senders | $CONCURRENCY |

## Ingestion

| Metric | Value |
| --- | --- |
| Accepted | $ACCEPTED / $EVENTS |
| Elapsed | ${INGEST_ELAPSED}s |
| Throughput | **${INGEST_RPS} events/s** |
| Latency p50 | ${P50}ms |
| Latency p95 | ${P95}ms |
| Latency p99 | ${P99}ms |
| Latency max | ${PMAX}ms |

Ingestion is two inserts in one transaction with no reads, so this is close to the
database's write throughput for the connection pool available.

## Rating

| Metric | Value |
| --- | --- |
| Rated | $RATED / $ACCEPTED |
| Drain after ingestion | ${DRAIN_ELAPSED}s |
| End to end | ${TOTAL_ELAPSED}s |
| Throughput | **${RATING_RPS} events/s** |

End-to-end covers the first request to the last rated row, so it includes rating that
overlapped with ingestion. It is the figure that answers "how long until the data is
billable".

## Work distribution

\`\`\`
$DISTRIBUTION
\`\`\`

## Correctness

$ACCEPTED accepted, $RATED rated, **$DOUBLE_BILLED double-billed**. Throughput is
uninteresting if the arithmetic stops holding under contention; it holds.
REPORT
    info "Report written to $EVIDENCE"
fi

exit "$FAILURES"
