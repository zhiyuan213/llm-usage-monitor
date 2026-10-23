# Operations runbook

## Configuration

Use `.env.example` as the configuration contract. `APP_SECRET` must contain at least 32 random characters and must survive restarts; changing it invalidates code hashes and prevents pending encrypted outbox messages from being read. Keep it in an encrypted secret store and include its recovery procedure with backups. Never commit `.env`.

Production requires TLS in front of the loopback-bound Nginx port, `PUBLIC_URL=https://app.metertrail.com`, and `SECURE_COOKIES=true`. Configure the website/console/ingest DNS and certificates explicitly. No DNS or cloud resources are created by Compose. Prefer a single console/API origin; the ingest domain routes only to the `/v1` API path. Protect management and monitoring ports from public ingress.

Configure real SMTP and remove Mailpit from public production environments. Configure Alertmanager's receiver and external availability monitoring; a local dashboard is not independent availability evidence. The Compose monitoring exporters target a Linux deployment; Docker Desktop shows its Linux VM, not complete Windows host metrics.

## Local tests and UI

`scripts/e2e.mjs` exercises registration, real outbox delivery through Mailpit, verification, session/CSRF, project and key creation, price configuration, 36-object ingestion, duplicate retries, batch completion, traces, costs, billing, alerts and audit. It writes test credentials only under ignored `.local/e2e-account.json`. It deliberately waits for the real 60-second verification cooldown.

## Releases

CI must pass compilation, integration/unit tests, source example checks, dependency scanning, and secret scanning. Image jobs run only after those gates. OCI artifacts and SHA-256 checksums are archived per commit. Publish/import the same verified images to your registry and record immutable digests; do not rebuild an unverified production image.

Before running `ops/release.sh`:

1. Review migration changes against a restored representative database; record lock duration and compatibility. Flyway migrations are additive; no destructive migration is automatically approved.
2. Import/publish the tested artifact and collect candidate digest, commit SHA, backup evidence and rollback digest.
3. Set `CANDIDATE_IMAGE`, `RELEASE_SHA`, and `APPROVED_SHA` (the human-approved exact SHA).
4. Configure an isolated synthetic tenant with sufficient units and `SMOKE_API_KEY`. Configure a candidate-only reachable endpoint, public smoke endpoint, and authenticated/restricted Prometheus URL as needed.
5. Run the script from the exact approved checkout. It starts the alternate slot, waits for readiness, runs candidate smoke, requires valid monitoring, validates Nginx, switches, updates the worker, runs live smoke and observes 15 one-minute samples.

Any missing metric blocks completion. Generate continuous synthetic traffic during low-load observation so latency histograms are meaningful. The script updates the Prometheus file-based target to the candidate and checks its exact instance plus the worker. It generates traffic between pre-cutover scrapes; the observation window still needs representative live/synthetic traffic. The previous API is retained for rollback. Rollback restores routing, monitoring targets and worker image, not database migrations or previously incurred fees. Billing discrepancies or cross-project leakage require freezing affected writes and a separate audited repair.

`ops/release.sh` currently automates API/worker slots. Frontend static artifact promotion must be coordinated with backwards-compatible APIs and independently archived; full atomic frontend promotion remains a launch gate. The repository has not performed a real production cutover.

## Backup and restore

`ops/backup.sh` streams a consistent custom-format `pg_dump` through `age` encryption, records a SHA-256 checksum, and uploads to an explicitly configured private `rclone` remote. It does not provision a bucket or credentials. Verify storage access and lifecycle rules separately. Schedule with the deployment host's scheduler after recovery objectives have been agreed.

`ops/restore.sh` requires an isolated PostgreSQL container/database named `metertrail_restore_*`. Decrypt and restore there, compare object counts, workspace-period units, ledger sums, API-key identity rows and audit samples. Test login/key behavior with restored application secrets. Current-period used units cannot be compared to all-time ledger totals without filtering periods.

An encrypted local dump is not proof of off-host recovery. Record an actual off-host restore drill, elapsed recovery time and data-loss boundary before production approval.

## Capacity

`ops/load.js` is a k6 ingestion harness. Set `BATCH_SIZE=1`, `50`, and `500` in separate runs with `EPS=1000 DURATION=30m`; use `EPS=3000 DURATION=5m` for burst testing. Give a dedicated nonproduction tenant enough quota, capture database/worker metrics, and do not use customer keys. Never turn off accounting merely to get a throughput number.

The harness counts events/sec separately from HTTP/sec. Add representative simultaneous trace queries at 20 QPS, 8 KiB edge events, revisions, duplicates and failure injection before accepting the target. Default budget-safe local smoke tests are not a capacity benchmark. Query/store architecture may need aggregation/partitioning after measurement.

## Incident handling

- `RATE_LIMIT_UNAVAILABLE`: verify Redis availability; accepted PostgreSQL data remains durable.
- Oldest pending >60 seconds: inspect worker health, database locks, errors and price data. Retry transaction failures without clearing pending batches or unit identities.
- A repeatedly failing batch is quarantined after 10 attempts with a 30-second backoff. Use Integration & API keys → Recent batches to replay after repairing the cause. To declare permanent failure, use the verified cancellation flow; only still-unmaterialized objects with no surviving update are eligible for a unit release. Closed-period corrections require separate review. A corrected higher revision may reserve a released unit again; replaying the canceled transport event does not resurrect it.
- Ledger mismatch: stop affected ingestion and compare workspace period with `usage_ledger`. Never manually reset usage without a reviewed correction ledger.
- Mail failures: inspect SMTP configuration and `outbox.attempts`; bodies are encrypted. Requeue after fixing delivery, respecting code expiry. Never expose secrets in logs.
- Payment failure: verify signature secret and clock. Use reconciliation and provider sandbox logs; do not trust frontend redirect status.
- Deleted account: active subscription must first be canceled through the provider portal. Email recovery when the old mailbox is inaccessible is a manual operator identity-verification process, not a bypass endpoint.


## Isolated verification and recovery limits

`IngestIntegrationTest` runs a real PostgreSQL custom-format dump/restore into a separate
`metertrail_restore_*` database, compares every public table, checks key hashes, billing
identities and encrypted mail, and confirms restored sequences accept writes. It also
queues 1,200 unique events plus transport replays before starting three concurrent
Workers. These checks do not certify off-host encryption, a recovery time objective,
or sustained production throughput.

`node --test scripts/release-rollback.test.mjs` executes the release shell script against
controlled Docker, smoke and monitoring command doubles. Candidate smoke, Nginx
validation, worker startup, live smoke and observation failures must preserve or restore
routing and monitoring; post-routing failures must restore the previous worker image.
This validates script control flow, not an actual production blue/green cutover.

For a notification that exhausted ten attempts, first repair the transport and identify
one unsent non-verification message by ID. Review its relevance before the following
operator action, then confirm `sent_at` is populated and the body cleared. Never reset
all attempts or resend expired authentication codes:

```sql
UPDATE outbox SET attempts = 0
WHERE id = :reviewed_notification_id AND sent_at IS NULL AND attempts >= 10
  AND subject <> 'MeterTrail verification / 验证码';
```

`MailOutboxIntegrationTest` verifies the targeted retry and that sent messages remain
ineligible. Exhausted-mail alert routing still needs operational configuration and an
actual receiver drill; a database recovery test does not prove notification delivery.
