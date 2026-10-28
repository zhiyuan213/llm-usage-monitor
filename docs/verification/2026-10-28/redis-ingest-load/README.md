# Redis ingestion load probe — 2026-10-28

This is a bounded local characterization of the uncommitted Redis transport, **not a production or sustained-capacity certification**. It ran on shared Docker Desktop (16 logical CPUs, ~39.1 GiB VM memory), with isolated API/worker/PostgreSQL/Redis and no production endpoints or credentials. Container caps: API 1.5 CPU/768 MiB; worker 1.5 CPU/768 MiB; PostgreSQL 2 CPU/1 GiB; Redis 1 CPU/512 MiB (maxmemory384 MiB), AOF `appendfsync always`, `noeviction`. The single workspace has sufficient synthetic Pro quota. About half the events are Trace and half generation Observation, with an actual configured model rate and token usage. Source baseline HEAD is d1715b0 plus current Redis changes; immutable runtime image and JAR digest are in report.json.

k6 0.54.0 uses constant arrival rate and checks HTTP202, p95<300ms, <1% failed HTTP, and zero dropped iterations. Exit99 is a failed load threshold, never treated as a pass.

| Phase | Target EPS | Events/request | Seconds | Events with HTTP202 | Non202 requests | HTTP p95 ms | Objects materialized/sec during load | Queued Redis events after load | Drained |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| batch50-1000 | 1000 | 50 | 60 | 60,000 | 0 | 15.67 | 728.3 | 16,000 | yes |
| batch50-3000 | 3000 | 50 | 60 | 114,450 | 1,312 | 12.42 | 1142.5 | 45,350 | yes |
| single-1000 | 1000 | 1 | 30 | 20,563 | 9,039 | 114.07 | 24.9 | 19,806 | no (180s bound) |

## Findings

- Batch50/1,000EPS: all 1,200 requests returned202; p9515.67ms. Nevertheless materialization lagged (~728EPS during the measured minute) and needed ~17.5s sampled recovery after load. Passing intake thresholds does not establish steady end-to-end capacity.
- Batch50/3,000EPS: the 50,000-event queue budget was reached. 1,312/3,601 requests failed (~36.4%). Successful reception averaged1,907.5EPS; materialization averaged1,142.5EPS during load. The accepted backlog drained in ~33.2s sampled recovery. The fast-rejection responses contribute to the low overall p95; this phase fails capacity requirements.
- Single-event/1,000EPS: only20,563/29,602 requests received202;9,039 failed, and399 target iterations were dropped. Reception averaged685.4EPS, while materialization during the congested load averaged24.9EPS. The subsequent quiet drain was roughly40–42EPS. The20,000-request queue bound also applies. It did not drain in180seconds; further load was stopped. The separately planned paused-consumer phase was therefore not run.
- The present consumer handles one client request per database transaction and ACK operation. Single-event messages amplify per-request persistence/transaction overhead. Low sampled CPU during quiet drain suggests CPU scaling alone is insufficient; precise disk/transaction attribution needs profiling. No implementation optimization was included in this measurement task.
- These tests lasted30–60seconds each. They do not satisfy a30-minute sustained-load/capacity gate, and the local disk/network results cannot be equated to the production host.

## Consistency check and retained data

After stopping only the isolated API and worker to obtain a consistent snapshot:

- HTTP202-confirmed events: **195,013**.
- Database-accepted events / workspace units / usage-ledger rows: **187,476**, all equal.
- Redis messages not yet committed to the database: **7,537 events**.
- Conservation: **187,476 + 7,537 = 195,013**, no missing confirmed event in this snapshot.
- Materialized objects:187,464; another12 database batches remained pending when the test worker stopped.
- Duplicates, business rejects and business error receipts:0. All93,685 materialized generation observations have a known cost and the expected0.00036USD estimate; no mismatched cost observed.

API and worker were stopped after measurement. Isolated Redis/PostgreSQL were then stopped with their Docker volumes retained, preserving the unprocessed test messages and evidence; production and unrelated containers were untouched. The remaining messages are **pending**, not claimed as fully processed. Full drain would require restarting these isolated consumers.

Raw k6 summaries/logs and timestamped resource/backlog samples are included. Secrets remain only in ignored local environment files and are not copied here. The repeatable workload is workload.js; the current setup/orchestrator are ignored local helpers under .local/redis-capacity/.
