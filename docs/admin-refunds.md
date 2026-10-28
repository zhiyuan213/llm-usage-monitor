# Administrator refunds

Administrators use **Administrator → Payments & refunds** (`/app/admin-payments`) to search payments by account email or transaction ID. The list is paginated. Ordinary users cannot access the API, even when opening the URL directly.

The Refund action supports a full Creem refund within seven elapsed days of the provider transaction timestamp. Preview reads the transaction and subscription from Creem and checks environment, customer, subscription, payment state, previous adjustments and the latest subscription payment. The administrator reviews the provider amount, enters a reason and types the transaction ID. An older payment with a newer renewal is directed to provider review so a refund cannot cancel a newer paid period. Paddle payments remain visible and must be handled in Paddle.

A unique, durable request per transaction is committed before `POST /v1/refunds`. Concurrent clicks, reloads and retries do not issue another refund. A timeout is `unknown`; **Reconcile status** reads the provider transaction and does not resubmit a refund. Provider `pending` / `requiresAction` states remain nonterminal. A succeeded refund is not a bank-arrival promise.

After a confirmed full refund of the latest subscription payment, the application separately cancels that subscription immediately. A cancellation failure remains visible and can be reconciled without issuing another refund. Access is stopped only after confirmed provider cancellation; retained data follows the existing grace-period policy. The refund request, administrator and reason are retained. Verified refund webhooks update adjustment records and the matching refund request; a partial adjustment cannot mark a full request succeeded.

Required Creem API permissions include transaction/subscription read, refunds write and subscriptions write. Test and production keys, products, webhook secrets and endpoints must match. Never put keys in Git or browser configuration.

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
