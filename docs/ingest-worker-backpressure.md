# Ingest admission and adaptive worker draining

This records the earlier gate-only stage and its test results. The default path
has since been replaced by [durable aggregated ingestion](durable-ingestion.md),
including physical read/write pools and bounded Worker grouping. The four-request
gate described below now applies only when the durable pipeline is disabled.

This extends the transaction preparation optimization described in
`ingest-transaction-timing.md`. Acceptance still commits to PostgreSQL before HTTP
200 with `status: pending`. No Redis acceptance queue is enabled.

## Consumer scheduling

`WorkerLoop` uses the dedicated `ingestWorkerScheduler`. Existing maintenance and
provider schedulers remain unchanged. SMTP waits and scheduled statistics queries
cannot occupy the consumer's scheduling thread.

The default consumer checks every 10ms while busy. Each drain slice processes up
to 1000 client batches or 250ms of elapsed time, whichever is reached first. Time
budgets are checked **between** batches; an in-flight transaction is never
interrupted merely because the slice budget expires. When no eligible batch is
found, the consumer avoids DB polling for 300ms. An unhandled DB failure backs off
for 1000ms and remains visible as an error.

Every client batch retains its own transaction, row-lock claim, revision handling,
completion update and failure retry/quarantine behavior. `SKIP LOCKED` still allows
multiple worker instances. There is no new transaction spanning a whole slice.
The manual `Worker.process()` helper retains its bounded ten-batch behavior for
operator/test invocations; production scheduling uses `WorkerLoop.poll()`.

## HTTP write admission

Only POST `/v1/events/batch` acquires an admission lease, before its DB key lookup.
The lease covers authentication queries, backlog checks, parsing and acceptance,
and is released on every return/exception. A saturated gate returns immediately:

```http
HTTP/1.1 503 Service Unavailable
Retry-After: 1
Content-Type: application/json

{"code":"INGEST_BUSY"}
```

A request rejected by this gate has not reserved quota or created a batch.
Clients must retry the same event IDs, with bounded retry/backoff. Existing backlog
and Redis-limiter unavailability responses also carry `Retry-After: 1`.

The configured default is four concurrent POST requests per API instance. With a
known positive Hikari pool size, it is capped at `max(1, poolSize - 2)` so ingestion
alone cannot occupy the entire pool when multiple slots exist. A one-connection
pool cannot provide read/write headroom. Actual limits are exported as gauges.

This is a writer concurrency budget, **not physical connection-pool isolation**.
Quota/batch GETs and console queries bypass the admission gate. Other controllers,
maintenance jobs and internal service calls still share the configured pool and
can consume its remaining connections. Limits are per API instance, not a global
workspace quota, and must be budgeted across replicas against DB capacity.

## Configuration

| Environment variable | Default | Meaning |
| --- | ---: | --- |
| `INGEST_MAX_INFLIGHT` | 4 | Maximum concurrent HTTP ingest POSTs before the pool-size cap |
| `WORKER_BUSY_POLL_MS` | 10 | Scheduling gap after a busy slice |
| `WORKER_IDLE_POLL_MS` | 300 | Idle database polling backoff |
| `WORKER_SLICE_MS` | 250 | Elapsed processing budget checked between batches |
| `WORKER_MAX_BATCHES_PER_SLICE` | 1000 | Hard batch-attempt count per slice |
| `WORKER_FAILURE_BACKOFF_MS` | 1000 | Backoff after an unhandled slice failure |

Defaults are wired through `application.yml`, `compose.yml` and `.env.example`.
Increasing the admission limit may reintroduce lock/connection queues. Increasing
worker throughput also increases DB write pressure; neither setting promises a
specific EPS without measurement.

## Metrics and verification

- `metertrail_ingest_inflight`: active leases.
- `metertrail_ingest_inflight_limit`: effective local limit.
- `metertrail_ingest_unavailable_total{code=...}`: bounded code categories
  `INGEST_BUSY`, `INGEST_BACKLOG_FULL`, `RATE_LIMIT_UNAVAILABLE`, `OTHER`.
- `metertrail_worker_slices_total{outcome="busy|idle|error"}`: drain decisions.
- Existing transaction-phase and backlog metrics remain available.

Correctness checks cover immediate rejection without reservation, read access
while the gate is saturated, release after malformed requests, idempotent lease
release, idle/error backoff and draining more than ten batches without rebilling.
Existing poison-batch, connection-loss, hard-kill and billing regressions remain
required. Count rejected requests and dropped load-generator iterations as failed
capacity targets; a low latency caused by fast 503s is not a throughput pass.

## Local verification on 2026-10-24

Backend `mvn verify` passed 94 tests with zero failures/errors/skips and packaged
successfully. `docker compose --env-file .env.example config --quiet` also passed.

The local 3000 EPS / batch50 two-minute retest passed with zero failed requests or
dropped iterations (write p95 24.12ms, query p95 10.55ms). Single-event 300 EPS
remains below the required acceptance rate: fast `INGEST_BUSY` rejections are
counted as failures. Host port-forwarding runs also observed client timeouts;
direct-container runs did not reproduce them, but dataset and quota consumption
differences prevent a definitive network-root-cause claim. No new-version long
soak, push or production deployment has been performed.

See [the complete retest report](verification/2026-10-24/local-capacity-worker-gate/README.md)
for failed scenarios, control limitations, data reconciliation and raw evidence.
