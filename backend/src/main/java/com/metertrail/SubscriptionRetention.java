package com.metertrail;

import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** Subscription transitions share the workspace lock with ingestion and payment callbacks. */
final class SubscriptionRetention {
  static void reconcile(Store store, TransactionTemplate tx) {
    tx.executeWithoutResult(s -> {
      store.db.update("UPDATE workspaces SET retention_grace_until=period_end+interval '30 days' "
          + "WHERE plan IN ('Core','Pro') AND subscription_status='active' AND period_end<=now() AND retention_grace_until IS NULL");
      for (var w : store.queries().queryForList("SELECT * FROM workspaces WHERE plan IN ('Core','Pro') "
          + "AND retention_grace_until<=now() AND (subscription_status IS DISTINCT FROM 'active' OR period_end<=now()) FOR UPDATE")) {
        ChargeLedger.close(store,w);
        store.db.update("UPDATE workspaces SET plan='Free',auto_overage=false,used_units=0,addon_units=0,"
            + "overage_units=0,addon_consumed=0,period_start=date_trunc('month',now()),"
            + "period_end=date_trunc('month',now())+interval '1 month' WHERE id=?",w.get("id"));
        store.audit(null,(UUID)w.get("id"),"billing.retention-downgrade","30-day renewal grace ended",true,null);
      }
    });
  }
}
