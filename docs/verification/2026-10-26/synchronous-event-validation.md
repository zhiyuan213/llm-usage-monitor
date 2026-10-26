# Synchronous HTTP event preflight

Request: reject simple parameter errors in the upload response instead of requiring a later batch-status query.

Contract: after key authentication and bounded JSON/envelope parsing, validate every event with the existing pure Protocol validator. Any invalid event returns HTTP 400 with the first error code at the top level and an `errors` list containing each invalid event's first error (`index`, `field`, `code`). Indices refer to the original input array. The entire request is rejected: no batchId, no enqueue, no batch row and no unit charge. Never echo payload values or credentials. State-dependent conflicts, deduplication, quota and retention remain protected by the transactional processing path. Old stored batches retain their result semantics. SDK permanent-400 handling is unchanged.

Pre-commit verification, 2026-10-26:
- Full `mvn -B -f backend/pom.xml verify`: BUILD SUCCESS, 176 tests, zero failures/errors/skips, isolated source snapshot excluding unrelated mail changes.
- Real MockMvc + PostgreSQL tests cover queue-enabled and fallback HTTP paths: immediate 400, original indices and fields, no async request, no queue/batch/ledger rows, valid neighbors not accepted, and admission permits released.
- Pure validation regression covers non-object events and avoids exposing payload text. Existing full ingest, export, billing, duplicate, revision and durable-recovery tests pass.
- Java SDK `mvn -B -f sdk/java/pom.xml verify`: BUILD SUCCESS. Node SDK 9 tests and Python SDK 6 tests pass.
- Frontend 29 tests and production build pass. Official registry audit (clean local npm CLI) reports zero vulnerabilities. Generated-example synchronization check passes.
- Browser confirms the new synchronous validation heading and example are present; 390px page has no horizontal overflow. Existing V2 styles reused.

Exact-commit backend, frontend-sdk, security and images CI remain required before deployment.
