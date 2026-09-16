# Manual testing guide

How to exercise the service by hand, and what each scenario is meant to prove.

The scripted checks in `scripts/` cover all of this automatically — this document is for
walking through it yourself, which is what the follow-up interview asks for.

---

## Start

```bash
./scripts/start.sh              # one instance on :8080
./scripts/start.sh --scale 3    # three instances behind nginx
./scripts/stop.sh --clean       # stop and delete the database
```

Everything below assumes:

```bash
export BASE="http://localhost:8080"
export TENANT="tenant-a"
export CUSTOMER="manual-$(date +%s)"       # unique, so runs do not collide
export PERIOD=$(date -u +%Y-%m)
export OCCURRED=$(date -u -v-1d +%Y-%m-%dT%H:%M:%SZ)   # BSD date; GNU: -d '1 day ago'
```

Seeded pricing for `tenant-a`:

| Code | Price | Valid |
| --- | --- | --- |
| `VEHICLE_REGISTRATION` | 2.00 → **2.50** | changes at 2026-07-01T00:00:00Z |
| `TITLE_TRANSFER` | 7.25 | open ended |
| `RECORD_LOOKUP` | 0.003333 | open ended — exercises rounding |

`tenant-b` prices `VEHICLE_REGISTRATION` at **3.75 EUR**, so any cross-tenant leak shows
up as a wrong amount and a wrong currency, not merely as a wrong row count.

---

## 1. Ingest a transaction

```bash
curl -s -X POST "$BASE/api/v1/transactions" \
  -H "X-Tenant-Id: $TENANT" -H 'Content-Type: application/json' -d "{
    \"eventId\": \"$(uuidgen | tr 'A-Z' 'a-z')\",
    \"customerId\": \"$CUSTOMER\",
    \"transactionCode\": \"VEHICLE_REGISTRATION\",
    \"occurredAt\": \"$OCCURRED\",
    \"metadata\": {\"quantity\": 2}
  }"
```

**Expect** `202` and `"status": "ACCEPTED"`.

**Proves** the request returns as soon as the event is durable. `202`, not `200`: the
work is accepted, rating has not happened yet.

---

## 2. Re-deliver it

Run the **exact same command** again, keeping the same `eventId`:

```bash
EVENT=$(uuidgen | tr 'A-Z' 'a-z')
for i in 1 2 3; do
  curl -s -o /dev/null -w "%{http_code} " -X POST "$BASE/api/v1/transactions" \
    -H "X-Tenant-Id: $TENANT" -H 'Content-Type: application/json' -d "{
      \"eventId\": \"$EVENT\", \"customerId\": \"$CUSTOMER\",
      \"transactionCode\": \"VEHICLE_REGISTRATION\",
      \"occurredAt\": \"$OCCURRED\", \"metadata\": {\"quantity\": 2}
    }"
done
```

**Expect** `202 200 200`.

**Proves** the retry worked as intended. It returns `200`, not `409`, because a
re-delivery is not a client error — a `4xx` would make integrations retry or alert over
correct behaviour. The `status` field distinguishes the cases.

---

## 3. Re-deliver with a different body

Same `$EVENT`, different quantity:

```bash
curl -s -X POST "$BASE/api/v1/transactions" \
  -H "X-Tenant-Id: $TENANT" -H 'Content-Type: application/json' -d "{
    \"eventId\": \"$EVENT\", \"customerId\": \"$CUSTOMER\",
    \"transactionCode\": \"VEHICLE_REGISTRATION\",
    \"occurredAt\": \"$OCCURRED\", \"metadata\": {\"quantity\": 99}
  }"
```

**Expect** `"status": "DUPLICATE"` with `"payloadConflict": true`.

**Proves** the first delivery wins — billing the second would double-charge — but the
discrepancy is recorded rather than hidden. It appears in the reconciliation report for
a human to judge.

---

## 4. See it rated

Wait a second, then:

```bash
curl -s -G "$BASE/api/v1/invoices/summary" -H "X-Tenant-Id: $TENANT" \
  --data-urlencode "customerId=$CUSTOMER" --data-urlencode "period=$PERIOD"
```

**Expect** a total of `5.0000` per event (2 units × 2.50) and `"status": "OPEN"`.

**Proves** the worker rated asynchronously, at the price in effect when the usage
occurred, and that the open period aggregates on read.

---

## 5. Trace the amount

```bash
curl -s -G "$BASE/api/v1/reconciliation/lines" -H "X-Tenant-Id: $TENANT" \
  --data-urlencode "customerId=$CUSTOMER" --data-urlencode "period=$PERIOD"
```

**Expect** each line to carry `quantity`, `unitPrice`, `amount` and `pricingRuleId`.
Check the arithmetic yourself: `2 × 2.500000 = 5.0000`.

Then fetch the rule:

```bash
curl -s "$BASE/api/v1/pricing-rules/2" -H "X-Tenant-Id: $TENANT"
```

**Proves** requirement 6 end to end: nothing in the system is a number without a
derivation, and the derivation is three requests deep at most.

---

## 6. Reconcile

```bash
curl -s -G "$BASE/api/v1/reconciliation/report" -H "X-Tenant-Id: $TENANT" \
  --data-urlencode "customerId=$CUSTOMER" --data-urlencode "period=$PERIOD"
```

**Expect** `"balanced": true`, with counts for `RATED` and `DUPLICATE`.

**Proves** the report checks its own arithmetic:

```
received = accepted + duplicates + rejected
accepted = rated + invoiced + unrated + failed + quarantined
```

`balanced: false` would mean a defect in the service, and `imbalance` would name the
equation that failed.

---

## 7. Rounding

`RECORD_LOOKUP` is priced at `0.003333`:

```bash
curl -s -X POST "$BASE/api/v1/transactions" \
  -H "X-Tenant-Id: $TENANT" -H 'Content-Type: application/json' -d "{
    \"eventId\": \"$(uuidgen | tr 'A-Z' 'a-z')\", \"customerId\": \"$CUSTOMER\",
    \"transactionCode\": \"RECORD_LOOKUP\",
    \"occurredAt\": \"$OCCURRED\", \"metadata\": {\"quantity\": 7}
  }"
```

**Expect** `0.0233`.

**Proves** rounding happens once, at the end. `7 × 0.003333 = 0.023331 → 0.0233`.
Rounding the unit price first would give a different figure, and the difference
compounds across an invoice.

---

## 8. Close a period, then send a late event

```bash
curl -s -X POST "$BASE/api/v1/invoices/close?customerId=$CUSTOMER&period=$PERIOD" \
  -H "X-Tenant-Id: $TENANT"
```

Now send another transaction dated in that now-closed period, wait a moment, and check
**both** periods:

```bash
NEXT=$(date -u -v+1m +%Y-%m)
curl -s -G "$BASE/api/v1/invoices/summary" -H "X-Tenant-Id: $TENANT" \
  --data-urlencode "customerId=$CUSTOMER" --data-urlencode "period=$PERIOD"   # unchanged
curl -s -G "$BASE/api/v1/invoices/summary" -H "X-Tenant-Id: $TENANT" \
  --data-urlencode "customerId=$CUSTOMER" --data-urlencode "period=$NEXT"     # adjustment
```

**Expect** the closed invoice's total to be **identical**, and the next period to show
the charge under `adjustmentAmount` with `"originPeriod"` naming the closed period.

**Proves** the late-arrival policy: a closed invoice is never modified, the charge is
not lost, and the reader can still tell what was consumed when.

---

## 9. Tenant isolation

Ask for the same customer as a different tenant:

```bash
curl -s -G "$BASE/api/v1/invoices/summary" -H "X-Tenant-Id: tenant-b" \
  --data-urlencode "customerId=$CUSTOMER" --data-urlencode "period=$PERIOD"
```

**Expect** a total of `0.0` — not an error, and not the data.

Then try to bill another tenant by editing the body:

```bash
curl -s -X POST "$BASE/api/v1/transactions" \
  -H "X-Tenant-Id: tenant-b" -H 'Content-Type: application/json' -d "{
    \"eventId\": \"$(uuidgen | tr 'A-Z' 'a-z')\", \"tenantId\": \"tenant-a\",
    \"customerId\": \"$CUSTOMER\", \"transactionCode\": \"VEHICLE_REGISTRATION\",
    \"occurredAt\": \"$OCCURRED\", \"metadata\": {\"quantity\": 1}
  }"
```

**Expect** `422` with a `tenantId` failure. The header is identity; the body is data to
validate against it.

### Proving it at the database, not just the API

The convincing check. Connect as the application role and issue a query with **no**
tenant filter at all:

```bash
docker compose exec postgres psql -U usage_app -d usage_rating -c "
  BEGIN;
  SET LOCAL app.tenant_id = 'tenant-b';
  SELECT tenant_id, count(*) FROM rated_transaction GROUP BY tenant_id;
  COMMIT;"
```

**Expect** only `tenant-b` rows, even though `tenant-a` rows exist in the same table.

Row-level security did that, not a `WHERE` clause. Note the role: `usage_app` has
`NOBYPASSRLS` and owns nothing. Running the same query as `usage_owner` returns
everything, because a table owner bypasses every policy — which is exactly why the
application does not connect as one.

---

## 10. Multi-instance coordination

```bash
./scripts/stop.sh --clean && ./scripts/start.sh --scale 3
./scripts/multi-instance-test.sh
```

Or by hand — send a burst, then ask which instance did what:

```bash
docker compose exec postgres psql -U usage_owner -d usage_rating -c "
  SET app.tenant_id = 'tenant-a';
  SELECT processed_by, count(*) FROM outbox_message
  WHERE processed_by IS NOT NULL GROUP BY processed_by;"
```

**Expect** more than one instance in the output, and no event billed twice.

**Proves** `SKIP LOCKED` hands disjoint batches to separate processes — which is a
different claim from the thread-level concurrency the unit tests cover, and the one the
brief actually asks about.

---

## 11. Failure handling

**A transaction code with no pricing rule:**

```bash
curl -s -X POST "$BASE/api/v1/transactions" \
  -H "X-Tenant-Id: $TENANT" -H 'Content-Type: application/json' -d "{
    \"eventId\": \"$(uuidgen | tr 'A-Z' 'a-z')\", \"customerId\": \"$CUSTOMER\",
    \"transactionCode\": \"NO_SUCH_CODE\",
    \"occurredAt\": \"$OCCURRED\", \"metadata\": {\"quantity\": 1}
  }"
```

Accepted, then `UNRATED` in the reconciliation report — never billed at zero, never
discarded, and retried indefinitely because the rule may be created tomorrow.

**A restart mid-flight.** Send a burst and restart an instance while it drains:

```bash
docker compose restart app
```

Nothing is lost: work already claimed is reclaimed after the stale-claim timeout, and
unclaimed work was never at risk. Re-run the reconciliation report afterwards — it still
balances.

---

## What to look at if something surprises you

```bash
docker compose logs -f app                  # application logs
docker compose exec postgres psql -U usage_owner -d usage_rating

# queue state
SET app.tenant_id = 'tenant-a';
SELECT status, count(*) FROM outbox_message GROUP BY status;

# anything stuck, and why
SELECT id, status, attempt_count, last_error FROM outbox_message
WHERE status IN ('FAILED','UNRATED','QUARANTINED');
```

Note the `SET app.tenant_id` on those queries: without it, row-level security returns
nothing — which is the mechanism working, not a broken connection.
