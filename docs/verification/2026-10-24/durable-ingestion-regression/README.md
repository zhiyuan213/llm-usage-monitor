# Durable ingestion regression verification

Executed locally on 2026-10-24 with Maven verify, Java 17 and real Testcontainers PostgreSQL.
All 125 tests passed, with zero failures, errors or skipped tests. The attached JSON records
suite totals and the SHA-256 of the built application artifact.

Coverage includes durable cross-request acceptance, original receipts, replay/idempotency,
transactional quota blocks, rollback/crash recovery, bounded queues, quarantine operations,
Worker aggregation and independent physical read/write pools. Compose configuration validation
also passed. This run uses the final 20 ms producer coalescing default.

This is correctness verification, not a successful capacity certification. Earlier load runs
and their failed zero-drop thresholds remain in the sibling capacity diagnostic directories.
No production deployment or GitHub Actions result is implied.
