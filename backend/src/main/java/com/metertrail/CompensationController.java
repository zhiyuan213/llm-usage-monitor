package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Explicit, audited permanent failure handling. No automatic deletion of accepted batches. */
@RestController
class CompensationController {
  final Store store;
  final Challenges challenges;

  CompensationController(Store store, Challenges challenges) {
    this.store = store;
    this.challenges = challenges;
  }

  @PostMapping("/api/projects/{project}/batches/{batch}/cancel")
  @Transactional
  Map<String, Object> cancel(
      @PathVariable UUID project,
      @PathVariable UUID batch,
      @RequestBody JsonNode body,
      HttpServletRequest req) {
    UUID user = SessionFilter.uid(req);
    var p = store.project(user, project);
    var w =
        store.db.queryForMap(
            "SELECT * FROM workspaces WHERE id=? FOR UPDATE", p.get("workspace_id"));
    var rows =
        store.db.queryForList(
            "SELECT * FROM ingest_batches WHERE id=? AND project_id=? FOR UPDATE", batch, project);
    if (rows.isEmpty()) throw new ApiError(404, "BATCH_NOT_FOUND");
    if (rows.get(0).get("status").equals("failed"))
      return Map.of("status", "failed", "released", 0);
    Support.require(rows.get(0).get("status").equals("quarantined"), "BATCH_NOT_QUARANTINED");
    Support.require(body.path("confirm").asBoolean(), "CONFIRMATION_REQUIRED");
    challenges.consume(
        user,
        "batch.cancel",
        project + ":" + batch,
        body.path("challengeId").asText(),
        body.path("code").asText(),
        req);
    // Only release an unmaterialized identity with no surviving update in another batch.
    // This single snapshot sees either another worker's pending event or its committed object.
    var refundable =
        store.db.queryForList(
            "SELECT b.* FROM billable_objects b WHERE b.project_id=? AND b.reservation_batch=? AND"
                + " b.charged AND NOT EXISTS(SELECT 1 FROM objects o WHERE"
                + " o.project_id=b.project_id AND o.type=b.type AND o.object_id=b.object_id) AND"
                + " NOT EXISTS(SELECT 1 FROM ingest_events e WHERE e.project_id=b.project_id AND"
                + " e.batch_id<>? AND NOT e.processed AND e.payload->>'type'=b.type AND"
                + " e.payload->>'objectId'=b.object_id)",
            project,
            batch,
            batch);
    int released = 0, releasedOverage=0, releasedAddon=0;
    for (var object : refundable) {
      int generation = ((Number) object.get("charge_generation")).intValue();
      String accepted = generation == 1 ? "accepted" : "accepted:" + generation;
      var ledger =
          store.db.queryForMap(
              "SELECT period_start,overage,addon_charge_id FROM usage_ledger WHERE project_id=? AND type=? AND object_id=?"
                  + " AND reason=?",
              project,
              object.get("type"),
              object.get("object_id"),
              accepted);
      Support.require(
          ledger.get("period_start").equals(w.get("period_start"))
              && store.db.queryForList("SELECT id FROM usage_charges WHERE workspace_id=? AND period_start=? AND kind='overage'",w.get("id"),w.get("period_start")).isEmpty(),
          "CLOSED_PERIOD_REQUIRES_REVIEW");
      int change =
          store.db.update(
              "INSERT INTO"
                  + " usage_ledger(workspace_id,project_id,type,object_id,units,reason,period_start)"
                  + " VALUES(?,?,?,?,-1,?,?) ON CONFLICT DO NOTHING",
              w.get("id"),
              project,
              object.get("type"),
              object.get("object_id"),
              "released:" + generation,
              w.get("period_start"));
      released += change;
      if(change>0 && Boolean.TRUE.equals(ledger.get("overage"))) releasedOverage++;
      if(change>0 && ledger.get("addon_charge_id")!=null){
        releasedAddon++;
        store.db.update("UPDATE usage_charges SET consumed_units=greatest(0,consumed_units-1) WHERE id=?",ledger.get("addon_charge_id"));
      }
      store.db.update(
          "UPDATE billable_objects SET charged=false WHERE project_id=? AND type=? AND object_id=?",
          project,
          object.get("type"),
          object.get("object_id"));
    }
    store.db.update(
        "UPDATE workspaces SET used_units=used_units-?,overage_units=greatest(0,overage_units-?),addon_consumed=greatest(0,addon_consumed-?) WHERE id=?", released, releasedOverage, releasedAddon, w.get("id"));
    store.db.update("UPDATE ingest_events SET processed=true WHERE batch_id=?", batch);
    store.db.update(
        "UPDATE ingest_batches SET status='failed',completed_at=now() WHERE id=?", batch);
    store.audit(
        user,
        (UUID) w.get("id"),
        "batch.permanent-failure",
        batch + ":released=" + released,
        true,
        req);
    return Map.of("status", "failed", "released", released);
  }
}
