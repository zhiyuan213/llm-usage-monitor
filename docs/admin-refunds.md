# Administrator refunds

Administrators use **Administrator → Payments & refunds** (`/app/admin-payments`) to search payments by account email or transaction ID. The list is paginated. Ordinary users cannot access the API, even when opening the URL directly.

The Refund action supports a full Creem refund within seven elapsed days of the provider transaction timestamp. Preview reads the transaction and subscription from Creem and checks environment, customer, subscription, payment state, previous adjustments and the latest subscription payment. The administrator reviews the provider amount, enters a reason and types the transaction ID. An older payment with a newer renewal is directed to provider review so a refund cannot cancel a newer paid period. Paddle payments remain visible and must be handled in Paddle.

A unique, durable request per transaction is committed before `POST /v1/refunds`. Concurrent clicks, reloads and retries do not issue another refund. A timeout is `unknown`; **Reconcile status** reads the provider transaction and does not resubmit a refund. Provider `pending` / `requiresAction` states remain nonterminal. A succeeded refund is not a bank-arrival promise.

After a confirmed full refund of the latest subscription payment, the application separately cancels that subscription immediately. A cancellation failure remains visible and can be reconciled without issuing another refund. Access is stopped only after confirmed provider cancellation; retained data follows the existing grace-period policy. The refund request, administrator and reason are retained. Verified refund webhooks update adjustment records and the matching refund request; a partial adjustment cannot mark a full request succeeded.

Required Creem API permissions include transaction/subscription read, refunds write and subscriptions write. Test and production keys, products, webhook secrets and endpoints must match. Never put keys in Git or browser configuration.

Updated cancellation behavior (2026-10-28): confirmed cancellation returns the current plan to Free immediately, preserving consumed usage and applying Free quota. Previous paid data retention remains separately protected by its existing grace deadline. Core and Pro can be purchased again using a checkout bound to the ended subscription. The earlier sandbox record below documents the behavior before this change.

When a refund initially returns `pending`, use **Reconcile status** after provider confirmation to complete subscription cancellation. The list explicitly shows cancellation still requiring reconciliation until that operation succeeds.

## Verification — 2026-10-28

- Linux Docker Maven `mvn -B -f backend/pom.xml verify`: 190 tests, zero failures/errors/skips; successful package. Windows Java loopback failures were isolated to the host runtime; Linux ran the complete integration suite.
- Frontend `npm test`: 47 tests passed; `npm run build`: successful, 11 public pages generated. Docker Node 24 `npm audit --audit-level=high --fetch-retries=0 --fetch-timeout=20000`: zero vulnerabilities. All three generated documentation/example synchronization checks passed.
- Browser end-to-end against isolated PostgreSQL/Redis/API and the actual frontend: test Core checkout paid USD 29; verified signed payment callback activated Core with 100,000 units. Admin preview showed the actual provider amount, test environment and seven-day deadline. Submitted refund through the new confirmation dialog.
- Transaction `tran_3xCDF5iw4M7HOZv5frm0IK`: approved full USD 29 refund `ref_5asMMhDuWgjMUvZ3zDRoc1`; refund adjustment recorded. The initial provider response was pending. Reconciliation confirmed the refund and immediately canceled the linked test subscription. Buyer ingestion status returned `subscription_inactive`, `can_accept_new=false`; consumed units remained unchanged.
- Desktop and 390px layouts inspected; tables scroll horizontally without page overflow. A wrapped reconciliation action was corrected with scoped button styles and table minimum width; frontend tests/build were repeated afterward.
- Temporary webhook disabled, dedicated callback route removed and original Caddy configuration restored (website HTTP 200); isolated server containers stopped. No real payment or refund was made. This verifies sandbox behavior only; production credentials and real bank settlement remain outside this test.
- Feature is implemented locally; no implementation commit, push or production deployment is claimed by this record.

Provider contracts: https://docs.creem.io/api-reference/endpoint/refund-payment and https://docs.creem.io/api-reference/endpoint/cancel-subscription.

## Refund diagnostics — 2026-10-30

A historical live refund submission returned a non-transient HTTP 4xx. The controller retained only `rejected`, so its exact provider error cannot be recovered. This does not establish a bank rejection or a particular permission/configuration cause.

Migration V32 adds the last failure's HTTP status, structured error code, redacted JSON message, operation stage, timestamp and application request ID. Raw provider bodies and exception messages are not logged or retained. Configured secrets, common credential patterns, URLs, emails, long tokens and card-like digit sequences are redacted from messages. Diagnostics remain admin-only. Warning logs contain only transaction ID, stage, status, safe code and request ID.

`payment.refund.request` still records the durable local claim. The additional `payment.refund.submission` audit event separately records whether the provider accepted the submission; neither event promises bank arrival. The console labels rejected submissions as **Refund request not accepted**, shows diagnostics and directs an operator to inspect Creem before reconciling. Legacy rows explicitly state that error details were not retained.

No automatic resubmission or retry endpoint was added. Uncertain HTTP responses and timeouts remain `unknown`; the unique transaction claim remains intact. Reconciliation can recognize an externally completed full refund and finish subscription cancellation without issuing another refund. It can itself cancel the subscription after a confirmed full refund; it is not a read-only operation. Cancellation failures retain independent diagnostics. Do not use production refunds merely to reproduce an error.

The historical transaction was independently observed as `succeeded`, with an approved USD 29 adjustment and an already canceled subscription/Free plan after the merchant used the Creem dashboard. Its refund-row cancellation marker still required reconciliation. No real refund, cancellation or database update was performed during this diagnosis/fix.

Local verification for the diagnostic change:
- Docker Maven/JDK 17: `mvn -B -f backend/pom.xml verify` — 241 tests, zero failures/errors/skips; BUILD SUCCESS. Includes 12 administrator-refund integration tests for rejected/uncertain outcomes, secret/email redaction, audit outcomes, duplicate suppression, external refund reconciliation and independent cancellation failures.
- `npm test` — 26 files, 79 tests passed. `npm run build` — successful production bundle and 11 public pages. Existing bundle-size warning and a Vite background dependency-scan shutdown warning were emitted after page generation; process exited zero.
- Docker Node 24: `npm audit --audit-level=high --fetch-retries=0 --fetch-timeout=20000` — zero vulnerabilities. Native Windows audit invocation was unavailable due to a local npm runtime error; it was not counted as a successful audit.
- `node scripts/sync-doc-examples.mjs --check`, `node scripts/sync-scenario-examples.mjs --check`, `node scripts/write-sdk-id-guides.mjs --check` — all passed.
- Actual AdminPayments component inspected with synthetic local fixtures on desktop and at 390px: errors/legacy explanation visible, table scroll contained inside its wrapper. No real refund submitted.
- `git diff --check` passed. Changes are local only: no commit/push, CI or deployment is claimed.
