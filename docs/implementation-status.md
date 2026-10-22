# Implementation and launch status

This is a runnable development release based on V1.1. **Do not describe it as production-approved or as having met the capacity targets.** No real customer data or external paid services were used for local verification.

Follow-up: Tencent Cloud SES API template delivery is now implemented alongside SMTP, including personal accounts without SMTP permission. Separate plain-text verification/notification templates and mocked provider-response tests are supplied. Real delivery still requires approved templates, restricted API credentials and server acceptance. See [SES setup](tencent-ses.zh-CN.md).

## Implemented

| Area | Delivered behavior | Evidence / remaining qualification |
| --- | --- | --- |
| Accounts | Email/password, verification, reset, revocable sessions, CSRF, two-address email change, account closure | Real registration and Mailpit email delivery exercised locally; reset/session revocation integration test |
| GitHub | OAuth state binding, provider-ID identity, verified-email requirement, explicit linking/unlinking, no email auto-merge | Code/configuration delivered; real OAuth App credentials still required for provider testing |
| Workspace/projects | Shared workspace plan, project CRUD and environment, separate workspace/project dashboards | Real local workspace, project and dashboard tested |
| Keys | 256-bit secrets, hash storage, one-time display, verification for creation/revocation, overlap rotation | Local real creation; isolation/revocation tests |
| Ingestion | Bounded JSON/gzip, 3 object types, durable 200, per-event validation/results | PostgreSQL and HTTP tests; real local 36-object batch |
| Identity/units | Canonical transport dedup, immutable object billing identity, revisions/conflicts, workspace transaction lock | Concurrent final-unit test, cap test, duplicates and revisions tests |
| Worker | SKIP LOCKED transactional recovery, backoff, quarantine, owner replay | Poison batch does not block following batch; replay retains original unit reservation |
| Failure compensation | Verified permanent failure of quarantined batch; idempotent eligible-unit release; corrected higher revision re-reserves once | PostgreSQL integration test. Closed-period corrections fail closed for operator review |
| Costs | Versioned USD rates, decimal arithmetic, cached-input subset, unknown costs, per-revision cost evidence | Cache calculation test; real local result $0.12705 for 12 illustrative calls |
| Queries | Traces/search/status/pagination, trace timeline, explicit Score associations, unmatched parents, customer/user/feature summaries | Real local trace, detail and summary checked |
| Plans | Free/Core/Pro, retention, default stop, consent/code-protected overage cap, progressive pricing | Exact-decimal boundary tests and concurrent reservation; add-ons intentionally unavailable |
| Payments | Sandbox checkout, raw HMAC verification, dedup/order protection, subscription state, transaction/adjustment records, portal endpoint, known-subscription reconciliation | Local signed fixtures verified; not equivalent to merchant sandbox acceptance |
| Alerts/audit | Rules, hourly windows/cooldown, email outbox, quota notices, append-only API audit | Local rule/audit paths checked; production receiver not configured |
| Frontend | 20 functional public/account/console views, 3 policy templates, EN/zh-CN, responsive blue/white layout | Type checking/build; language preference/API tests; browser login, language/form preservation and trace detail |
| SDKs/examples | Bounded async Node/Python/Java source SDKs; 7 REST language examples; generated multilingual-doc tabs | Node/Python tests; Java compile/runtime tests; CI example syntax/compile checks |
| Operations | Compose blue/green slots, non-root app/web images, health checks, shutdown grace, Prometheus/Grafana/Alertmanager/exporters, 3 dashboards | Core Compose stack runs locally; monitoring config validation and source gates |
| Release/backups | SHA/digest/approval gates, candidate/live smoke, required metrics, rollback, encrypted off-host backup and isolated restore scripts | Gate unit tests reject missing/unhealthy metrics; shell syntax validated. No production release or off-host restore claimed |

## Local acceptance evidence

The full-stack smoke registers a synthetic account through the real HTTP API, reads its code from Mailpit, creates a project and a verified API key, configures an illustrative model price, ingests 12 explicit traces + 12 observations + 12 scores, polls completion, replays the exact batch, and verifies:

- 36 accepted objects and 36 charged units.
- 36 duplicates on replay, **0 additional units**.
- 12 model calls, 36,480 known tokens, and **$0.12705** estimated model cost.
- A selected trace returns its Trace, Observation, and Score.
- Billing, alert-rule creation and audit endpoints return real persisted data.

Alert evaluation checks event times in the previous hour, excluding future events. Ingestion accepts at most five minutes of positive clock skew. Each rule persists its last notified object revision; unchanged evidence cannot trigger another notification after cooldown or worker restart. New matches captured during the one-hour cooldown are merged and delivered once even if their event times have since left the window. Notifications record the trigger-window value, new matching object count, and UTC event time range. A corrected higher revision is treated as new evidence if it still matches. V8 seeds existing notifications to avoid resending historical evidence on upgrade. This is application-level alert deduplication; ambiguous provider delivery failures can still cause an email retry.

The local test account is stored only in ignored `.local/e2e-account.json`; it is not a production credential or included in commits. The browser is left on the local application for review.

## Required before public launch

1. **Merchant/OAuth/SMTP setup and external acceptance.** Real GitHub login/link conflicts; real transactional mail deliverability; Paddle sandbox subscription, renewal, failed payment, cancellation, refund, duplicate and out-of-order notification scenarios. Configure product price IDs and customer portal. Enable live endpoints only after merchant approval.
2. **Outstanding commercial rules.** Add-on quantities/prices/expiry/order of consumption; upgrade/downgrade proration; cancellation access; failed-payment grace; refunds and taxes. The current paid path fails closed on inactive/expired subscription periods. It does not invent these policies.
3. **Live overage collection.** The exact units/overage reservation ledger is implemented; charging the provider for accrued usage and finalizing immutable invoices must be connected to the approved merchant model. Do not sell automatic live overage before this gate is completed.
4. **Capacity and storage proof.** Execute the full 1,000 EPS 30-minute runs for 1/50/500-event batches, 3,000 EPS bursts, concurrent 20 QPS queries, restarts and response-loss scenarios. Current queries use canonical PostgreSQL rows; summary tables/partitioning and cold archive infrastructure should follow measured requirements. Pro retains actual detail in PostgreSQL; three-year economics and archive latency are unverified.
5. **Production operations proof.** Configure domain/DNS/TLS, real metrics/notification receivers and independent external monitoring; run blue/green cutover/rollback against the actual environment; coordinate frontend artifact promotion; verify immutable registry digests. No cloud resources or public production endpoints have been deployed.
6. **Recovery/privacy policy.** Confirm financial/audit retention, recovery objectives and old-mailbox recovery policy; complete an encrypted off-host restore exercise. Policy pages are clearly labeled templates pending operator identity/contact/jurisdiction.

## Deliberate scope choices

- One owner per workspace, no team invitations, gateway, model resale, SSO, Kubernetes or Enterprise tier.
- The three object kinds share a logical objects table. Worker locking is transaction-scoped rather than a heartbeat lease; rollback releases work immediately.
- No fabricated model rates, prompt content, customer revenue/margins, supplier usage, SDK registry packages, capacity claims or SLA.
- All tests and examples use synthetic local data; homepage preview figures are explicitly illustrative.

## Primary integration references

- [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [GitHub OAuth authorization flow](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps)
- [Paddle webhook signatures](https://developer.paddle.com/webhooks/about/signature-verification/)
- [Paddle webhook delivery](https://developer.paddle.com/webhooks/about/respond-to-webhooks/)
