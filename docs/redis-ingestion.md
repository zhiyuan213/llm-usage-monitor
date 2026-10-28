# Redis reception and buffering

2026-10-28 implementation. PostgreSQL remains authoritative for identities, subscriptions, quota, usage ledgers, stored objects, price evidence and processing outcomes.

## Request lifecycle

1. The API authenticates the project key, applies rate/body limits and validates the envelope and event field shapes.
2. One Lua operation checks bounded request/event/byte budgets, appends the full request to Redis Streams, and stores a project-scoped pending receipt. Return HTTP **202**, `{batchId,received,status:"pending"}`, after Redis acknowledges the write. No quota reservation or business acceptance is implied.
3. The worker consumer group reads up to50 requests at once, coalescing for at most20ms when a group is not full. Group by workspace/project and split at500 events, configured byte budget or request count. Inside one PostgreSQL transaction per group, take sorted receipt identity locks, batch revalidate keys, reuse AggregatedIngest to batch quota/identity/event writes, and bulk-save independent database receipts. Never merge projects. A committed receipt fences duplicate consumers.
4. Only after each PostgreSQL group transaction commits does one Lua operation acknowledge/delete that group of stream messages, decrement budgets and delete pending receipts. A crash before acknowledgment replays the committed receipt without charging again. Abandoned pending deliveries are reclaimed after 60 seconds. Transient database/Redis failures leave messages available for retry; messages are never trimmed to make room.
5. The existing materialization worker writes queryable objects and model cost snapshots. `GET /v1/batches/{batchId}` first resolves the authoritative database result; before consumption it returns the project-scoped Redis pending receipt. Quota/key rejection after 202 is a final completed batch with rejected indices and error codes. Syntactically invalid input is still rejected before queueing.

## Configuration and durability

- Compose enables `INGEST_REDIS_ENABLED=true`; direct application runs can opt in with that variable. Set `INGEST_REDIS_CONSUMER_ENABLED=true` only on consumers (Compose worker), false on API slots. PostgreSQL legacy acceptors remain available to drain earlier requests.
- Redis must use `appendonly yes`, `appendfsync always`, `maxmemory-policy noeviction` and a persistent `/data` volume. Application startup rejects weaker durability/eviction settings. Compose provides a `redis-data` volume and 384 MiB Redis maxmemory / 512 MiB container memory.
- Shared queue budgets use the existing `INGEST_QUEUE_MAX_REQUESTS`, `INGEST_QUEUE_MAX_EVENTS`, `INGEST_QUEUE_MAX_BYTES`. Full/unavailable Redis returns 503; it never silently falls back to in-memory acceptance. A response timeout can still mean the write succeeded: retry unchanged event identities.
- Persistence protects Redis process/container restart with its retained volume. The supplied single-node configuration does **not** guarantee survival of host/disk loss or arbitrary replica failover. AOF fsync on every append trades throughput for receipt durability. No EPS capacity claim has been established.
- PostgreSQL outcome receipts are retained eight days, matching existing batch cleanup. Queue payloads/receipts have no expiry before commit and ACK. Redis credentials and payloads must not be logged.
- Observe `metertrail_redis_ingest_queue_requests/events/bytes`, `metertrail_redis_ingest_received`, `metertrail_redis_ingest_retry`, and metrics collection errors, alongside database worker backlog. A permanently unprocessable message remains pending for diagnosis rather than being discarded.

## Rollout and rollback

Apply V24 before enabling the transport. Back up PostgreSQL and Redis data first. When moving an existing ephemeral Redis deployment to the persistent volume, preserve existing Redis contents (including rate-limit state) and verify AOF/noeviction settings before starting API and worker. Drain old PostgreSQL acceptance and materialization queues. During rollback, stop new Redis submissions first and keep Redis consumers running until its stream and pending deliveries are empty; turning off the transport with queued receipts would hide their pending status. Never delete Redis volume/stream to clear an incident.

Integration coverage includes a pre-business 202 receipt, pending ownership, duplicate charging, asynchronous quota/key errors, commit-before-ACK replay, Redis process restart/AOF recovery, abandoned-delivery claim, and bounded backlog rejection. 

## Verification — 2026-10-28

- `mvn -B -f backend/pom.xml verify` in Maven/Java17 Docker with PostgreSQL and Redis Testcontainers: **214 tests passed**, zero failures/errors/skips. Redis-specific integration suite: **8 tests passed**. Evidence: `.local/redis-ingest-verify.log`.
- Real Redis process shutdown/restart with AOF retains queued data; abandoned consumer delivery is reclaimed. Commit-before-ACK crash replay charges once. Concurrent consumers serialize the final workspace unit. Database write failure keeps the message pending; quota, revoked key, event conflict, higher revision and project ownership are covered. Materialization and status lookup through the Redis receipt alias are exercised.
- SDK checks: Node `node --test sdk/node/test.mjs` **9 passed**; Python `python -m unittest discover -s sdk/python -p 'test_*.py'` **6 passed**; Java `mvn -B -f sdk/java/pom.xml verify` **4 passed** (Windows uses the existing short Unix-domain temp directory JVM option).
- All three generated documentation/example synchronization checks passed. `docker compose config --quiet` and `git diff --check` passed.
- A recovery-test setup initially restarted the whole Docker container, allowing Docker Desktop to reassign its ephemeral mapped port. The final test restarts the Redis process under a test supervisor, retaining the endpoint and genuinely reloading AOF. An ArrayNode test assertion was corrected; the complete suite above was rerun afterward.
- These are local checks. This change has not been pushed or deployed, and no new throughput/EPS claim is made.



## Batch-consumer optimization — 2026-10-28

- Redis reads are bounded to50 client requests; a non-full read may coalesce for at most20ms. Database groups additionally respect existing group-events (default500), flush-bytes (default2MiB) and request budgets.
- Existing AggregatedIngest preserves each original batchId, agent, per-event index and result, with one workspace quota lock per same-project group. Receipt locks are sorted, receipt lookup/key validation/result insertion are batched. Distinct projects use separate transactions.
- ACK/deletion/budget updates use one Lua operation per committed group, retaining XDEL-guarded idempotence. Failed database groups remain pending. Batch replay after commit-before-ACK is covered.
- `mvn -B -f backend/pom.xml verify`:218 tests passed, zero failures/errors/skips; Redis suite12 tests. Node/Python/Java SDK checks and generated-example synchronization passed. Evidence `.local/redis-batch-verify.log` and `.local/redis-batch-sdk-*.log`.
- This supersedes the single-request consumer performance profile; prior load results remain historical baseline evidence. New comparative load results are recorded separately.

Final optimized verification:219 backend tests passed; see [comparative load results](verification/2026-10-28/redis-batched-load/README.md). All377,709 confirmed events in this probe were materialized and ledger-balanced; single-event1,000EPS and sustained3,000EPS capacity gates are still not met.
