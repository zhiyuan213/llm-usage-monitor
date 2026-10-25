# Subscription data retention

Free retains a rolling 30 days, Core 90 days, and Pro 1096 days (approximately three years). Paid retention requires a valid subscription; buying one month does not purchase three years of storage after cancellation.

- An active subscription scheduled to cancel stays active through its paid period.
- Expiry without a callback starts a 30-day renewal window at `period_end`. A non-active subscription callback starts it at the earlier of the effective event time and the recorded period end. Repeated callbacks cannot extend an existing deadline.
- New ingestion is rejected while the paid subscription is inactive or expired. During the renewal window the historical retention cutoff is frozen, so existing retained records remain readable.
- A valid active-subscription callback clears the deadline. Renewing before cleanup preserves retained history.
- The hourly retention worker locks each expired workspace, closes the usage ledger, disables automatic overage, and changes it to Free. Free usage counters start in the current UTC calendar month. The same pass deletes analytics older than the Free 30-day window. Cleanup may occur up to an hour after the deadline.
- Account, payment and audit evidence are separate from analytics retention. Projects and credentials are not deleted by this transition. Later renewal cannot restore deleted analytics.

Migration V18 gives already inactive/expired paid workspaces a full 30 days from migration, preventing immediate historical deletion during rollout. No existing paid data is deleted by the migration itself.

Verification includes expiry without callbacks, retry-stable deadlines, renewed access, frozen historical cutoff, one-time Free counter reset, and the existing per-plan query/export/purge regression suite. Test data must remain in isolated local PostgreSQL containers; do not age real production subscriptions for testing.
