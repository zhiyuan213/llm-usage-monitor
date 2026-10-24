package com.metertrail;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Exact-size aggregation reservations, never API-private expiring/float quota balances. */
@Component
class QuotaBlocks {
  final Store store;

  QuotaBlocks(Store store) {
    this.store = store;
  }

  UUID reserve(UUID workspace, UUID project, int requested) {
    UUID id = UUID.randomUUID();
    store.db.update(
        "INSERT INTO ingest_quota_blocks(id,workspace_id,project_id,requested_units)"
            + " VALUES(?,?,?,?)",
        id,
        workspace,
        project,
        requested);
    store.db.queryForObject(
        "SELECT set_config('metertrail.quota_block',?,true)", String.class, id.toString());
    return id;
  }

  void settle(UUID id, UUID workspace) {
    store.db.update(
        "UPDATE ingest_quota_blocks SET allocated_units=(SELECT coalesce(sum(units),0) FROM"
            + " usage_ledger WHERE quota_block_id=?), consumed_units=(SELECT coalesce(sum(units),0)"
            + " FROM usage_ledger WHERE quota_block_id=?), period_start=(SELECT period_start FROM"
            + " workspaces WHERE id=?),state='settled',settled_at=now() WHERE id=?",
        id,
        id,
        workspace,
        id);
    store.db.queryForObject("SELECT set_config('metertrail.quota_block','',true)", String.class);
  }
}
