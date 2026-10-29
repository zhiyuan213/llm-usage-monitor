# Restore Free natural-month usage on paid access termination

Implemented 2026-10-29:

- V25 preserves the currently known workspace plan/period/usage in billing_usage_periods. Subsequent paid activations and renewals archive the outgoing counters within the workspace-locked payment transaction, before resetting the new paid period.
- Cancellation/refund termination and renewal-grace expiry archive paid usage and restore the current UTC calendar month's Free counter. A previous month's Free counter is not reused. Repeated callbacks/reconciliation do not reset an already restored Free workspace.
- Covers both Creem and Paddle activation paths and their shared termination logic. Closing automatic renewal without ending paid access is unchanged. Existing objects and usage ledger are retained; paid consumption is not charged against the restored Free month.
- Migration preserves current known counters only. Free counters already overwritten by an older upgrade are not reconstructable from the workspace counter; no historical Free usage is fabricated from paid usage or event timestamps.

Validation:

- `mvn -B -f backend/pom.xml verify` in maven:3.9-eclipse-temurin-17 with local Docker/Testcontainers: BUILD SUCCESS, 222 tests, zero failures/errors/skips; full log `.local/free-usage-verify.log`.
- Includes actual signed Creem payment/cancellation callbacks: Free 1000 → Core 0 → paid 23 → Free 1000; duplicate cancellation; a second cycle restores the updated Free 1007 counter; cross-month restores zero while retaining the previous month's 1000.
- Admin refund integration verifies restored Free 1000 and archived paid 17. Ingest integration verifies restored 49999 enforces the 50000 Free quota, plus grace expiry restores prior Free usage once.
- Database migration V25 applied successfully in full integration tests. No frontend/API response/SDK contract changes.
- `git diff --check`: passed. Not pushed or deployed.

- Desktop launcher fallback: Python unittest discover -s tools/model-tester -v: 4 tests pass. Launcher prefers adjacent standalone EXE, then py, then python; standalone packaging is a separate local deliverable.
