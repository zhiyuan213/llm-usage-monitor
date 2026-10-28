# Ended subscriptions and repurchase

Confirmed `canceled` / `expired` subscriptions return the current workspace plan to Free. Scheduled cancellation during an active paid period keeps that paid plan until the provider confirms it has ended. Other recoverable states, such as paused or past due, keep their existing billing management flow.

Free has 50,000 included units. Consumed usage is preserved when a paid subscription ends, paid add-on / overage entitlement counters are cleared, and the Free period follows the UTC calendar month. Cancellation does not reset usage or grant another allowance. Payment and usage charge records remain available.

The previous paid retention plan and its existing grace deadline are stored separately from the current tier. During that window the paid historical cutoff remains protected. Afterward Free retention applies, without resetting Free usage. A new paid subscription clears the old retention grace. Migration V22 updates existing ended paid workspaces using the same entitlement rule.

The Free card is current after cancellation; paid cards offer a new checkout. A checkout intent stores the previous subscription ID. A verified new payment can replace only the canceled subscription for which that checkout was created. Old or unrelated checkout events cannot replace a newer subscription. This applies to both Paddle and Creem. The old subscription remains recorded for payment history and provider reconciliation.

## Local verification — 2026-10-28

- `mvn -B -f backend/pom.xml verify` in Linux Maven/Java 17 with Docker Testcontainers: 194 tests, zero failures/errors/skips; build successful. Includes complete `IngestIntegrationTest` export coverage and new cancellation, Free quota, repurchase and delayed webhook regressions.
- `npm test`: 49 tests passed. `npm run build`: production build successful, 11 public pages generated.
- Node 24 Docker `npm audit --audit-level=high --fetch-retries=0 --fetch-timeout=20000`: zero vulnerabilities.
- `node scripts/sync-doc-examples.mjs --check`, `node scripts/sync-scenario-examples.mjs --check`, `node scripts/write-sdk-id-guides.mjs --check`: passed.
- The public policy date assertion was updated with the changed cancellation policy and a regression asserting the Free entitlement wording.
- Upgraded the isolated database used for the earlier real Creem sandbox payment/refund. V22 converted its canceled Core workspace to Free while preserving the original grace deadline. The actual ingestion-status API returned `ready`, `can_accept_new=true`, `quota_remaining=50000` and the UTC calendar-month end.
- The real frontend against that isolated backend displayed Free as current, enabled “Reopen Core”, preserved payment/refund history and separated the paid data grace notice. Desktop 1920×1080 and mobile 390px layouts checked; mobile had no page horizontal overflow.
- Clicking “Reopen Core” created a new actual Creem sandbox checkout (`ch_2RcVPB3njbadoJDdryOkWi`) and navigated to the provider test checkout. No second payment was submitted. New-payment activation and stale-event rejection were verified by the signed webhook integration tests.
