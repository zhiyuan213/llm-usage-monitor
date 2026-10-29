package com.metertrail;

import java.util.Map;

/** Called inside the same transaction and workspace lock as subscription transitions. */
final class BillingUsage {
  static void save(Store store, Map<String, Object> w) {
    store.db.update(
        "INSERT INTO billing_usage_periods(workspace_id,plan,period_start,period_end,used_units)"
            + " VALUES(?,?,?,?,?) ON CONFLICT(workspace_id,plan,period_start) DO UPDATE"
            + " SET period_end=excluded.period_end,used_units=excluded.used_units",
        w.get("id"), w.get("plan"), w.get("period_start"), w.get("period_end"), w.get("used_units"));
  }

  static long currentFreeUnits(Store store, Map<String, Object> w) {
    return store.db.queryForObject(
        "SELECT coalesce((SELECT used_units FROM billing_usage_periods WHERE workspace_id=?"
            + " AND plan='Free' AND period_start=(date_trunc('month',now() AT TIME ZONE 'UTC')"
            + " AT TIME ZONE 'UTC')),0)",
        Long.class, w.get("id"));
  }
}
