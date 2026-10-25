# Creem billing integration

This deployment prepares the signed webhook and a gated checkout adapter. It does **not** switch new checkouts to Creem. `CREEM_CHECKOUT_ENABLED` defaults to false; retain this until merchant approval and sandbox acceptance are complete.

## Configuration

Server `.env` values: `CREEM_API_KEY`, `CREEM_API_URL`, `CREEM_CORE_PRODUCT`, `CREEM_PRO_PRODUCT`, `CREEM_WEBHOOK_SECRET`. Never put secrets in Git, browser configuration, logs, or acceptance screenshots. Production API is `https://api.creem.io`; sandbox is `https://test-api.creem.io`. Products, keys and signing secrets must belong to the same environment.

Endpoint: `POST https://metertrail.com/webhooks/creem`.

Subscribe to `checkout.completed`, `subscription.active`, `subscription.paid`, `subscription.update`, `subscription.scheduled_cancel`, `subscription.canceled`, `subscription.past_due`, `subscription.unpaid`, `subscription.expired`, `subscription.paused`, `subscription.trialing`, `refund.created`, `dispute.created`.

The endpoint verifies raw-body HMAC-SHA256 using `creem-signature`, validates the environment and records a hash-only receipt. Replays with the same event ID and body are acknowledged; a changed body under the same ID is rejected. Failed database transactions are not marked successful, allowing provider retries. Unknown/unmapped events are acknowledged without granting access, including dashboard synthetic examples.

## Entitlements and coexistence

- Existing workspaces and checkout intents default to Paddle. Provider ownership prevents a Creem intent from overwriting a Paddle subscription.
- Server-created, workspace-bound checkout intents select the product; browser-selected prices or success URL parameters never grant entitlement.
- Only verified paid events grant/renew access. Sparse completed checkouts retrieve the authoritative subscription period before applying. Active/update notifications alone do not restore suspended access.
- Same-period payment notifications preserve usage. UTC period boundaries are normalized to database microsecond precision. A later paid period resets period usage once.
- Scheduled cancellation retains access through the paid period; immediate cancellation, expiry, pause, unpaid and past-due states stop new ingestion and preserve the existing retention grace policy.
- Refund and dispute notices are recorded separately from subscription state. A refund record alone does not assert that the provider canceled a subscription.
- Legacy reconciliation and usage collection remain scoped to Paddle. Creem plan changes, add-on purchases and automatic overage collection are not enabled by this deployment. Creem ingestion stops at the included quota.

## Remaining activation gates

Merchant approval; sandbox checkout and verified return; paid/renewal failures and webhook reordering; cancellation and refund reconciliation; customer portal; duplicate-checkout handling; Creem-specific plan changes and add-on/overage collection. Until these pass, do not enable the checkout flag or describe Creem payments as production-accepted.

Official references: [webhooks](https://docs.creem.io/code/webhooks), [checkout API](https://docs.creem.io/api-reference/endpoint/create-checkout), [subscription API](https://docs.creem.io/api-reference/endpoint/get-subscription), [customer portal](https://docs.creem.io/features/customer-portal).
