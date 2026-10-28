# Ingest transaction timing and first-stage optimization

This records the first-stage implementation. For the current default aggregation,
quota-block and pool architecture, see [durable ingestion](durable-ingestion.md).
The new path records workspace-lock timings once per aggregate; the legacy
per-request `total`/transaction-entry timers do not measure the whole new queue.

The ingestion endpoint still returns HTTP 202 with `status: pending` after the
PostgreSQL acceptance transaction commits. This change does not enable a Redis
acceptance queue or relax quota checks.

## Changes

- Validate each event and prepare its event/object hashes and serialized payload
  before opening the acceptance transaction. Invalid events retain their original
  index and are rejected individually inside the transaction.
- Reuse prepared values in both the fresh batch shortcut and the revision/replay
  path. Identity, retention, subscription and quota decisions remain under the
  workspace lock.
- Read paid, unconsumed add-on packs only when the workspace has an available
  add-on balance. Update a pack only if this transaction consumes it.
- Read unresolved overage collection records at most once per request, only when
  a new charge actually requires authorized automatic overage.
- Skip unchanged workspace usage updates on replay or non-billable revisions,
  avoiding unnecessary row versions while retaining workspace serialization.
- Require an active paid subscription in the fresh batch shortcut, matching the
  existing per-event path. Inactive subscriptions cannot bypass quota rejection.

## Metrics

`metertrail_ingest_phase_seconds` is a histogram with a bounded `phase` label.
It does not include workspace IDs, project IDs, payloads or credentials.

| Phase | Measurement |
| --- | --- |
| `prepare` | Event validation, hashes and serialization before transaction acquisition |
| `transaction_acquire` | TransactionTemplate entry through callback entry; includes connection acquisition/begin for a new transaction |
| `workspace_lock_acquire` | Locking SELECT round trip, including DB execution, network and lock wait; **not** pure PostgreSQL lock wait |
| `workspace_lock_held` | Successful lock query return through transaction completion, including commit/rollback; excludes time spent waiting to acquire the lock |
| `quota_lookup` | Optional add-on pack read and its local preparation |
| `overage_lookup` | Optional unresolved overage collection lookup |
| `event_identity_lookup`, `object_identity_lookup` | Fresh batch shortcut's identity lookups |
| `ledger_insert`, `identity_insert`, `event_insert` | Fresh batch shortcut's three bulk insert round trips |
| `transaction_body` | Acceptance callback, including lock acquisition and writes |
| `transaction_completion` | Callback exit through afterCompletion, including commit/rollback; not isolated WAL flush time |
| `total` | Preparation through return/exception; when joining an existing transaction, outer commit occurs later |

Timers overlap: do not add their percentiles. Transaction completion and lock-held
timers use transaction synchronization, including for rollback. When called inside
an existing transaction, completion timers finish with that outer transaction.
Database execution failures can prevent individual query timers from producing a
sample; total/body/completion timers still describe the failed call as applicable.

For pure lock wait, correlate these metrics with PostgreSQL wait events and
sampled SQL statistics. Hikari's existing connection acquisition metrics provide
additional pool detail.

Example p95 for held time:

```promql
histogram_quantile(0.95,
  sum by (le) (rate(metertrail_ingest_phase_seconds_bucket{phase="workspace_lock_held"}[5m]))
)
```

## Verification boundary

Run the backend correctness suite, including mixed invalid/replayed/revised events,
quota rollback, add-on and overage transitions, and maximum batch idempotency.
Capacity and sustained-load testing are separate; compilation or correctness tests
do not demonstrate improved requests/second or a specific lock duration.

2026-10-24: final local `mvn -B -o verify` passed in the Maven Java 17 Linux
container with an isolated Testcontainers PostgreSQL 16 database: 86 tests,
zero failures/errors/skips, including 58 ingest integration cases. Packaging also
passed. Windows-native attempts encountered loopback/Docker environment failures;
the complete suite was subsequently executed successfully in Linux, without
disabling tests. No capacity run, push or production deployment was performed.
