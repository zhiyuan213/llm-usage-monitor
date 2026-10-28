# Durable aggregated ingestion

Historical PostgreSQL transport design. The Compose default is now [Redis reception and buffering](redis-ingestion.md); this legacy acceptor remains available to drain existing requests.

## Contract and data flow

`POST /v1/events/batch` still returns HTTP **200**, with `status: pending`, only
after authoritative acceptance commits. It does not acknowledge an in-memory
buffer or merely a queue insertion. The downstream Worker materializes accepted
events; batch-status polling remains the completion API.

1. Authenticate the project key, enforce admission/rate/body limits and validate
   the envelope.
2. Place the envelope in a bounded local buffer. A dedicated writer coalesces
   requests into PostgreSQL `ingest_accept_queue`, using synchronous commits.
3. An acceptor claims queued requests under a workspace advisory transaction lock,
   revalidates their keys and combines requests from the same project. A group is
   bounded by request count, event count and bytes. Different API instances may
   consume each other's durable requests.
4. Lock the workspace once for the group, apply subscription/period/identity/quota
   rules, write original client batches and event/usage records, settle the quota
   block and complete queue receipts in **one database transaction**.
5. After commit, complete waiting HTTP responses. A separate read-pool poller
   delivers receipts accepted by another instance.

Acceptance uses a workspace `FOR NO KEY UPDATE` lock: quota writers still
serialize, while foreign-key key-share checks need not wait for a primary-key
change that never happens. Queue insertion acquires its foreign-key locks before
the global storage-budget lock, avoiding the inverse budget/workspace lock order.
V13 also indexes `billable_objects.reservation_batch`: deleting an empty temporary
aggregate batch otherwise makes PostgreSQL scan all billed identities to check
the foreign key. The query-plan regression test ensures the FK lookup can use
this index. Before/after evidence on about 750,000 identities is preserved in
`verification/2026-10-24/local-capacity-durable-fk-diagnostic`.

Original client batch IDs, agents, event indices, duplicate/conflict handling and
quota rejection boundaries are preserved. Aggregation is within a project, not
across project ownership boundaries. Different projects in a workspace still
share the authoritative workspace quota lock.

The downstream Worker also materializes bounded same-project groups (100 client
batches / 500 accepted events by default), updating each original batch's status.
Object upserts select the highest revision of each identity in a group; cost
entries retain each accepted revision. A failed group rolls back all its
materialization, then its members retry individually to isolate poison input.
This does not reserve quota or charge usage again. Set `WORKER_GROUP_BATCHES=1`
to disable downstream coalescing without disabling durable acceptance.

## Quota allocation and recovery

`ingest_quota_blocks` records an exact-size transactional allocation for a group.
Its requested size is an upper bound; allocated/consumed units are the actual
billable identities accepted. Duplicate events and rejected events consume no
units. `usage_ledger.quota_block_id` links every allocated unit to its block.
A deferred database constraint prevents committing an unsettled block or one
whose ledger total, workspace, project or period does not match.

These are **per-group allocations**, not long-lived, prepaid API-local quota
leases. Workspace locking changes from once per HTTP request to once per group;
it is not removed entirely. This avoids estimating when a dead process's unused
lease is safe to return, and keeps add-on, overage-cap and period-change decisions
in the existing authoritative transaction.

Before queue insertion commits, a crash gives no success and the client retries.
After insertion but before acceptance, the durable row remains queued. During
acceptance, a connection/process failure rolls back identities, usage, block and
receipt together. After acceptance commits, retrying the same event IDs uses
existing idempotency rules and cannot charge those identities again.

Timeouts/disconnections may occur after persistence or commit. HTTP 503
`INGEST_ACCEPTANCE_UNCERTAIN` means the result is uncertain: retry the **same event
IDs**, never generate new IDs to retry an existing event. No 200 is emitted early.

PostgreSQL must have `fsync=on`; startup rejects a disabled setting. Queue and
acceptance transactions explicitly use `synchronous_commit=on`. This is local
database durability, not an off-machine backup or protection from loss of the
database disk. Redis is not the durable ingest queue.

## Independent connection pools

`metertrail-write` and `metertrail-read` are separate Hikari pools with distinct
physical connections and transaction managers. Both connect to the configured
authoritative PostgreSQL database; this is not replica routing. Read connections
enforce `default_transaction_read_only=on`. Writes and queries inside write
transactions use the write connection; read-only snapshot endpoints explicitly
use the read transaction manager.

Defaults are 8 write + 8 read connections **per JVM**, with minimum idle 1 each
and a 1-second acquire timeout. Budget database `max_connections` for all API and
Worker JVMs, migrations and administration. Separate pools prevent writers from
occupying every query connection; CPU, disk and database locks remain shared.

Unfiltered trace-page totals keep the existing one-second exact-snapshot cache,
with owner-scoped single-flight refresh and refresh-ahead after half a second.
A single bounded refresh worker prevents concurrent cache misses from occupying
the read pool. Expired cache entries are not used as error fallbacks;
failed/full/timed-out refreshes return explicit 503 errors with `Retry-After`.
The count SQL computes the authorized retention cutoff once so PostgreSQL can use
an index-only scan. This background count uses one read-only transaction and
`SET LOCAL max_parallel_workers_per_gather=0`, keeping other PostgreSQL workers
available for ingestion without changing session settings in the shared pool.
Filtered counts retain their existing semantics.
Billing/quota reads do not use this analytical count cache.

## Configuration

Defaults and supported environment variables are in `.env.example`,
`application.yml` and `compose.yml`. Key defaults:

| Setting | Default |
| --- | ---: |
| Pipeline / acceptor enabled on API | true / true |
| Pipeline / acceptor enabled on Worker | false / false |
| In-flight local requests / bytes | 2048 / 32 MiB |
| Queue writer requests / coalesce delay | 200 / 20 ms |
| Acceptance group events / bytes | 500 / 2 MiB |
| Worker group batches / accepted events | 100 / 500 |
| Durable queued requests / events / bytes | 20,000 / 50,000 / 128 MiB |
| HTTP wait / receipt poll | 5 seconds / 20 ms |
| Processing attempts | 10 |
| Completed receipt retention | 600 seconds |

Queue bounds are enforced transactionally across instances by
`ingest_queue_budget`. Quarantined input continues occupying this budget.
Local byte reservations remain held until the writer actually releases the
serialized payload, even if the waiting HTTP request has timed out. Acceptance
claims metadata first and fetches payloads only for the selected bounded group.
Completed receipt payloads are cleared immediately; terminal receipts are cleaned
in bounded batches. Usage blocks and ledgers remain for reconciliation.

## Failure handling and operations

Transient processing failures retry with a delay and finite attempt count.
At the limit, requests become `quarantined`: their original payload is retained,
no accepted usage is committed, and healthy requests continue processing.
Owners can use authenticated, CSRF-protected endpoints:

- `GET /api/projects/{project}/ingest-queue`: quarantine metadata, without keys or payloads.
- `POST /api/projects/{project}/ingest-queue/{id}/replay`: reset a quarantined request for replay after fixing its cause.
- `DELETE /api/projects/{project}/ingest-queue/{id}`: explicitly discard quarantined input and release its queue-storage budget; never cancels accepted usage.

Replaying revalidates the original key and current subscription/quota. Revoked
keys and canceled subscriptions do not gain access from old queued requests.
Quarantine is not silently auto-deleted.

Watch queue requests/events/bytes, buffered bytes, waiting requests, group size,
workspace lock acquisition/hold timings and receipt-poll errors. Monitor
quarantined rows and compare block allocations to the usage ledger. A full queue
returns 503 with `Retry-After`; it does not turn failed persistence into success.

V13 is additive. Apply migrations before starting the new application. To revert
to the legacy HTTP acceptance path, first stop new ingress, drain/reconcile queued
and quarantined requests, then disable `INGEST_PIPELINE_ENABLED`. Do not simply
disable every acceptor while queued input remains. Do not drop financial blocks
or the ledger linkage during rollback.

## Verification

`DurableIngestIntegrationTest` uses a real PostgreSQL with fsync enabled. It covers
cross-request grouping, original receipts/indices, replay/idempotency, quota and
period boundaries, add-ons/overage caps, concurrent projects, revoked keys,
subscription cancellation, transaction constraints, connection termination,
actual producer-JVM hard kill, cross-instance consumption, bounded buffers/queue,
quarantine/replay/cancel, HTTP asynchronous commit ordering, physical read-only
connections and query availability when the write pool is exhausted.

Capacity evidence is recorded separately under
`docs/verification/2026-10-24/local-capacity-durable` and the sibling diagnostic
folders. The recorded 600-second run accepted 599,774 requests without HTTP
failure and reconciled all accepted data, but dropped 227 scheduled iterations;
it did **not** pass the strict zero-drop capacity gate. Later diagnostic runs
also exposed synchronous-commit and large-history count latency. They are not
successful capacity certifications. The final defaults require a new capacity
run; regression verification alone does not certify throughput. Local performance
is not an assertion of public-network or production-disk capacity.
