# Batched Redis consumer optimization and comparative probe

2026-10-28, local only; implementation and evidence remain uncommitted/unpushed/undeployed. The exact tested JAR SHA256 and immutable image ID are in report.json. Hardware/environment is the same isolated Docker Desktop host as the [original single-request consumer baseline](../redis-ingest-load/README.md); other applications exist on the host. API1.5CPU/768MiB, worker1.5CPU/768MiB, PostgreSQL2CPU/1GiB, Redis1CPU/512MiB(maxmemory384MiB). AOF appendfsync=always and noeviction were retained. No production traffic or customer data was used.

## Changes

- Read up to50 client requests and coalesce for at most20ms when not full. Group by workspace/project, then enforce500-event, byte and request bounds.
- Reuse existing database aggregation: sorted receipt locks, batch receipt/key lookup, one workspace quota lock per group, batch event/identity/ledger processing, independent original batch IDs, agents and error indices, bulk outcome insertion.
- One post-commit Lua operation acknowledges/deletes a group and decrements budgets idempotently. Batch reclaim and crash-after-commit replay remain safe.
- Reuse fully configured thread-safe JSON parsers/serializers. Strict trailing-token rejection and canonical hashes are unchanged; a concurrent parsing regression test covers200 calls across8 threads.

## Verification

`mvn -B -f backend/pom.xml verify`: **219 tests passed**, zero failures/errors/skips, including12 Redis integration tests. Evidence `.local/redis-batch-final-verify.log`. Batch transaction sharing, per-request quota outcomes, batch ACK failure replay and project separation are covered in addition to the existing recovery tests. Node9/Python6/Java4 SDK tests and all three generated-example synchronization checks passed. No frontend implementation changed.

## Load results

k6 0.54.0 constant arrival rate, max200 VUs. HTTP202 check, p95<300ms, <1% HTTP failures and zero dropped iterations are separate gates. Exit99 means the phase failed one or more gates; a zero response-error count does **not** compensate for dropped iterations.

| Phase | Target EPS | Events/request | Seconds | Received EPS | Materialized EPS during load | Non202 requests | p95 ms | Dropped iterations | Sampled drain seconds | k6 exit |
|---|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|
| single-1000 | 1000 | 1 | 60 | 740.1 | 659.5 | 0/44409 | 477.69 | 15592 | 9.8 | 99 |
| single-3000 | 3000 | 1 | 60 | 1092.5 | 801.7 | 18583/84133 | 195.40 | 95868 | 25.0 | 99 |
| batch50-1000 | 1000 | 50 | 60 | 1000.8 | 1000.8 | 0/1201 | 11.16 | 0 | 2.0 | 0 |
| batch50-3000 | 3000 | 50 | 60 | 2960.8 | 2305.8 | 48/3601 | 34.65 | 0 | 28.6 | 99 |
| buffer-recovery | 3000 | 50 | 10 | 3005.0 | 0.0 | 0/601 | 18.89 | 0 | 34.7 | 0 |

- Single-event1,000EPS: all44,409 requests actually issued received202, but15,592 target iterations were dropped and p95 was477.7ms. Actual received rate740.2EPS, materialization659.5EPS. This **does not pass** the1,000EPS target. Compared with the original30-second single-event probe (24.9 materialized EPS under load), the measured materialization interval is about26x faster; durations/order/warm-up differ, so this is indicative rather than controlled capacity certification. Sampled API CPU reached~143% of its150% cap: entrance CPU is now a relevant bottleneck.
- Single-event3,000EPS still fails:18,583 non202 requests and95,868 dropped target iterations. Accepted queue drained in~25seconds afterward.
- Batch50/1,000EPS passes all gates:60,050 events, zero response errors/drops, p9511.16ms, no persistent backlog. The boundary iteration accounts for the extra50 events over60,000.
- Batch50/3,000EPS improved materially but still fails:48/3,601 requests non202 (~1.33%, baseline~36.4%). Received2,960.8EPS and materialized2,305.8EPS during load; sampled backlog recovery~28.6s. Queue protection remains enabled rather than hiding overload by expanding its budget.
- Paused consumer:30,050 events were accepted with zero errors while processing was intentionally stopped. Restarting the consumer drained the backlog in~34.7seconds, including startup and sampling overhead. This validates buffering/recovery, not sustained3,000EPS processing.
- Each ordinary phase is60seconds; this is not the30-minute sustained-capacity gate or a production SLA. Resource/queue snapshots and raw k6 summaries are included.

## Final consistency

All **377,709 HTTP202-confirmed events** equal database accepted events, workspace units, usage-ledger rows and materialized objects. Redis stream/events and pending database batches are zero. No duplicates, rejected database events or terminal business errors. All188,627 generation observations have the expected0.00036USD estimate; no mismatched or unknown model cost.

Only the isolated test containers were stopped after the consistent final snapshot, retaining their volumes. Earlier baseline/intermediate test environments and their evidence remain separate. No deployment or push was performed.
