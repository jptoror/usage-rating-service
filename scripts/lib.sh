#!/usr/bin/env bash
# Shared helpers for the scripts in this directory.
#
# Sourced, never executed directly. Everything here is deliberately plain POSIX-ish
# bash with no dependencies beyond curl, and jq only where output is formatted.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT="${TENANT:-tenant-a}"
CUSTOMER="${CUSTOMER:-customer-42}"

# --- output ----------------------------------------------------------------

if [ -t 1 ]; then
    BOLD=$'\033[1m'; GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'
else
    BOLD=""; GREEN=""; RED=""; YELLOW=""; RESET=""
fi

# All progress output goes to STDERR, never stdout.
#
# Callers capture a helper's stdout to assert on it -- `RESPONSE=$(step ...)` -- so a
# status line printed to stdout would be captured as part of the value. That produced
# empty assertions that looked like service failures when the service was fine.
section() { printf '\n%s== %s ==%s\n' "$BOLD" "$1" "$RESET" >&2; }
info()    { printf '   %s\n' "$1" >&2; }
pass()    { printf '   %sPASS%s  %s\n' "$GREEN" "$RESET" "$1" >&2; }
fail()    { printf '   %sFAIL%s  %s\n' "$RED" "$RESET" "$1" >&2; FAILURES=$((FAILURES + 1)); }
warn()    { printf '   %sWARN%s  %s\n' "$YELLOW" "$RESET" "$1" >&2; }

FAILURES=0

# Asserts two values are equal. Used by the end-to-end script so a failure is a
# non-zero exit code rather than something a reader has to spot by eye.
assert_eq() {
    local expected="$1" actual="$2" what="$3"
    if [ "$expected" = "$actual" ]; then
        pass "$what ($actual)"
    else
        fail "$what: expected '$expected', got '$actual'"
    fi
}

# --- api -------------------------------------------------------------------

# A UUID without requiring uuidgen, which is missing on some minimal images.
uuid() {
    if command -v uuidgen >/dev/null 2>&1; then
        uuidgen | tr 'A-Z' 'a-z'
    else
        python3 -c 'import uuid; print(uuid.uuid4())'
    fi
}

# Current and next billing period, in the YYYY-MM form the API expects.
current_period() { date -u +%Y-%m; }
next_period() {
    # BSD date (macOS) and GNU date disagree on relative-date syntax.
    date -u -v+1m +%Y-%m 2>/dev/null || date -u -d '+1 month' +%Y-%m
}
days_ago() {
    date -u -v-"$1"d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "$1 days ago" +%Y-%m-%dT%H:%M:%SZ
}

# POST a transaction. Echoes the response body; the event id is $1 so a caller can
# re-deliver the same event deliberately.
send_transaction() {
    local event_id="$1" quantity="${2:-2}" occurred_at="${3:-$(days_ago 1)}"
    local code="${4:-VEHICLE_REGISTRATION}" tenant="${5:-$TENANT}" customer="${6:-$CUSTOMER}"

    curl -sS -m 15 -X POST "$BASE_URL/api/v1/transactions" \
        -H "X-Tenant-Id: $tenant" \
        -H 'Content-Type: application/json' \
        -d "{
              \"eventId\": \"$event_id\",
              \"tenantId\": \"$tenant\",
              \"customerId\": \"$customer\",
              \"transactionCode\": \"$code\",
              \"occurredAt\": \"$occurred_at\",
              \"metadata\": {\"source\": \"e2e-script\", \"quantity\": $quantity}
            }"
}

# Same, but reports only the HTTP status. Used where the status is the assertion.
send_transaction_status() {
    local event_id="$1" quantity="${2:-2}" occurred_at="${3:-$(days_ago 1)}"
    local code="${4:-VEHICLE_REGISTRATION}" tenant="${5:-$TENANT}"

    curl -sS -m 15 -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/transactions" \
        -H "X-Tenant-Id: $tenant" \
        -H 'Content-Type: application/json' \
        -d "{
              \"eventId\": \"$event_id\",
              \"tenantId\": \"$tenant\",
              \"customerId\": \"$CUSTOMER\",
              \"transactionCode\": \"$code\",
              \"occurredAt\": \"$occurred_at\",
              \"metadata\": {\"quantity\": $quantity}
            }"
}

invoice_summary() {
    local period="$1" tenant="${2:-$TENANT}" customer="${3:-$CUSTOMER}"
    curl -sS -m 15 -G "$BASE_URL/api/v1/invoices/summary" \
        -H "X-Tenant-Id: $tenant" \
        --data-urlencode "customerId=$customer" \
        --data-urlencode "period=$period"
}

close_period() {
    local period="$1" tenant="${2:-$TENANT}"
    curl -sS -m 30 -X POST "$BASE_URL/api/v1/invoices/close?customerId=$CUSTOMER&period=$period" \
        -H "X-Tenant-Id: $tenant"
}

reconciliation_report() {
    local period="$1" tenant="${2:-$TENANT}" customer="${3:-$CUSTOMER}"
    curl -sS -m 15 -G "$BASE_URL/api/v1/reconciliation/report" \
        -H "X-Tenant-Id: $tenant" \
        --data-urlencode "customerId=$customer" \
        --data-urlencode "period=$period"
}

reconciliation_lines() {
    local period="$1" code="${2:-}" tenant="${3:-$TENANT}"
    if [ -n "$code" ]; then
        curl -sS -m 15 -G "$BASE_URL/api/v1/reconciliation/lines" \
            -H "X-Tenant-Id: $tenant" \
            --data-urlencode "customerId=$CUSTOMER" \
            --data-urlencode "period=$period" \
            --data-urlencode "transactionCode=$code"
    else
        curl -sS -m 15 -G "$BASE_URL/api/v1/reconciliation/lines" \
            -H "X-Tenant-Id: $tenant" \
            --data-urlencode "customerId=$CUSTOMER" \
            --data-urlencode "period=$period"
    fi
}

# Reads one field out of a JSON response without requiring jq.
json_field() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

# Waits until the outbox worker has drained, rather than sleeping a fixed amount.
# Polls the reconciliation report until nothing is left in a pending state.
wait_for_rating() {
    local period="$1" attempts="${2:-30}"
    for _ in $(seq 1 "$attempts"); do
        local pending
        pending=$(reconciliation_report "$period" | python3 -c "
import sys, json
states = {s['state']: s['count'] for s in json.load(sys.stdin)['states']}
print(states.get('ACCEPTED', 0))
" 2>/dev/null || echo 1)
        [ "$pending" = "0" ] && return 0
        sleep 1
    done
    warn "worker still has pending work after ${attempts}s"
    return 0
}

wait_for_health() {
    local attempts="${1:-60}"
    for _ in $(seq 1 "$attempts"); do
        if curl -sS -m 3 "$BASE_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
            return 0
        fi
        sleep 2
    done
    printf '%sService did not become healthy at %s%s\n' "$RED" "$BASE_URL" "$RESET" >&2
    return 1
}
