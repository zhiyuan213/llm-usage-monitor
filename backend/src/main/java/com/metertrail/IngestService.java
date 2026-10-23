package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IngestService {
  @org.springframework.beans.factory.annotation.Value("${metertrail.usage-collection-enabled:false}")
  boolean collectionEnabled;
  final Store store;
  final MeterRegistry metrics;

  IngestService(Store store, MeterRegistry metrics) {
    this.store = store;
    this.metrics = metrics;
  }

  @Transactional
  public Map<String, Object> accept(UUID project, JsonNode b) {
    // Serialize by workspace before touching identity rows; all projects share this quota.
    var w =
        store.db.queryForMap(
            "SELECT w.* FROM workspaces w JOIN projects p ON p.workspace_id=w.id WHERE p.id=? AND"
                + " p.deleted_at IS NULL FOR UPDATE OF w",
            project);
    UUID workspace = (UUID) w.get("id");
    String plan = (String) w.get("plan");
    long used = ((Number) w.get("used_units")).longValue();
    long addonConsumed=((Number)w.get("addon_consumed")).longValue();
    var packs=store.db.queryForList("SELECT id,units,consumed_units FROM usage_charges WHERE workspace_id=? AND period_start=? AND kind='addon' AND credited AND NOT revoked AND status='paid' ORDER BY created_at,id",workspace,w.get("period_start"));
    long before = used;
    long overageUnits=((Number)w.get("overage_units")).longValue();
    Instant end = ((Timestamp) w.get("period_end")).toInstant();
    if (!end.isAfter(Instant.now()) && plan.equals("Free")) {
      store.db.update(
          "UPDATE workspaces SET"
              + " used_units=0,period_start=date_trunc('month',now()),period_end=date_trunc('month',now())+interval"
              + " '1 month' WHERE id=?",
          workspace);
      used = 0;
      w = store.db.queryForMap("SELECT * FROM workspaces WHERE id=?", workspace);
      before = 0;
    }
    boolean collectionBlocked = !store.db.queryForList("SELECT id FROM usage_charges WHERE workspace_id=? AND kind='overage' AND amount>0 AND status IN ('failed','unknown','processing','submitted') LIMIT 1",workspace).isEmpty();
    UUID batch = UUID.randomUUID();
    store.db.update(
        "INSERT INTO ingest_batches(id,project_id,agent,received) VALUES(?,?,?::jsonb,?)",
        batch,
        project,
        b.path("agent").toString(),
        b.path("events").size());
    var fast = fastFresh(project, workspace, w, packs, batch, b, used, overageUnits, addonConsumed, before);
    if (fast != null) return fast;
    int accepted = 0, duplicates = 0, rejected = 0, index = 0;
    List<Map<String, Object>> errors = new ArrayList<>();
    for (JsonNode e : b.path("events")) {
      try {
        Protocol.event(e);
        String eventHash = Support.hash(Support.canonical(e));
        UUID eventId = Support.uuid(e.path("eventId").asText());
        var previous =
            store.db.queryForList(
                "SELECT content_hash FROM ingest_events WHERE project_id=? AND event_id=?",
                project,
                eventId);
        if (!previous.isEmpty()) {
          if (!eventHash.equals(previous.get(0).get("content_hash")))
            throw new ApiError(400, "EVENT_ID_CONFLICT");
          duplicates++;
          index++;
          continue;
        }
        String type = e.path("type").asText(), object = e.path("objectId").asText();
        long revision = e.path("revision").asLong();
        var content = e.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) content).remove("eventId");
        String contentHash = Support.hash(Support.canonical(content));
        var identities =
            store.db.queryForList(
                "SELECT * FROM billable_objects WHERE project_id=? AND type=? AND object_id=?",
                project,
                type,
                object);
        Instant started = Instant.parse(e.path("startedAt").asText());
        Instant now = Instant.now();
        boolean fresh = identities.isEmpty();
        boolean needsCharge = fresh || !(boolean) identities.get(0).get("charged");
        int generation =
            fresh
                ? 1
                : ((Number) identities.get(0).get("charge_generation")).intValue()
                    + (needsCharge ? 1 : 0);
        if (!fresh) {
          var old = identities.get(0);
          long rev = ((Number) old.get("revision")).longValue();
          if (revision < rev) {
            errors.add(Map.of("index", index, "code", "STALE_REVISION"));
            remember(project, eventId, eventHash, batch, index, e, true);
            duplicates++;
            index++;
            continue;
          }
          if (revision == rev) {
            if (!contentHash.equals(old.get("content_hash")))
              throw new ApiError(400, "REVISION_CONFLICT");
            remember(project, eventId, eventHash, batch, index, e, true);
            duplicates++;
            index++;
            continue;
          }
          Support.require(
              ((Timestamp) old.get("first_accepted_at"))
                      .toInstant()
                      .isAfter(now.minus(Duration.ofDays(Billing.retention(plan))))
                  && started.isAfter(now.minus(Duration.ofDays(Billing.retention(plan))))
                  && !started.isAfter(now.plusSeconds(300)),
              "OUTSIDE_RETENTION");
        } else {
          Support.require(
              !started.isBefore(now.minus(Duration.ofDays(7)))
                  && !started.isAfter(now.plusSeconds(300)),
              "OUTSIDE_TIME_WINDOW");
        }
        if (needsCharge) {
          boolean baseAvailable=used-overageUnits-addonConsumed < Billing.included(plan);
          Map<String,Object> pack=null;
          if(!baseAvailable) for(var candidate:packs) if(((Number)candidate.get("consumed_units")).longValue()<((Number)candidate.get("units")).longValue()){pack=candidate;break;}
          boolean overage=!baseAvailable && pack==null;
          boolean allowed=!overage || (!plan.equals("Free") && collectionEnabled && !collectionBlocked
              && (boolean)w.get("auto_overage")
              && Billing.overage(Math.addExact(100001,overageUnits)).compareTo((BigDecimal)w.get("overage_cap"))<=0);
          if (!allowed
              || (!plan.equals("Free")
                  && (!end.isAfter(now) || !"active".equals(w.get("subscription_status")))))
            throw new ApiError(429, "QUOTA_EXCEEDED");
          store.db.update(
              "INSERT INTO"
                  + " usage_ledger(workspace_id,project_id,type,object_id,units,reason,period_start,overage,addon_charge_id)"
                  + " VALUES(?,?,?,?,1,?,?,?,?)",
              workspace,
              project,
              type,
              object,
              generation == 1 ? "accepted" : "accepted:" + generation,
              w.get("period_start"),
              overage,
              pack==null?null:pack.get("id"));
          if(overage) overageUnits++;
          if(pack!=null){addonConsumed++;pack.put("consumed_units",((Number)pack.get("consumed_units")).longValue()+1);}
          used++;
        }
        store.db.update(
            "INSERT INTO"
                + " billable_objects(project_id,type,object_id,revision,content_hash,reservation_batch,charge_generation,charged)"
                + " VALUES(?,?,?,?,?,?,?,true) ON CONFLICT(project_id,type,object_id) DO UPDATE SET"
                + " revision=excluded.revision,content_hash=excluded.content_hash,charged=true,charge_generation=excluded.charge_generation,reservation_batch=CASE"
                + " WHEN billable_objects.charged THEN billable_objects.reservation_batch ELSE"
                + " excluded.reservation_batch END",
            project,
            type,
            object,
            revision,
            contentHash,
            batch,
            generation);
        remember(project, eventId, eventHash, batch, index, e, false);
        accepted++;
      } catch (ApiError err) {
        rejected++;
        errors.add(Map.of("index", index, "code", err.code));
        metrics.counter("metertrail_events_rejected", "reason", err.code).increment();
      }
      index++;
    }
    if (accepted == 0
        && duplicates == 0
        && errors.stream().anyMatch(e -> e.get("code").equals("QUOTA_EXCEEDED")))
      throw new ApiError(429, "QUOTA_EXCEEDED");
    store.db.update("UPDATE workspaces SET used_units=?,overage_units=?,addon_consumed=? WHERE id=?", used, overageUnits, addonConsumed, workspace);
    for(var pack:packs)store.db.update("UPDATE usage_charges SET consumed_units=? WHERE id=?",pack.get("consumed_units"),pack.get("id"));
    long included = ChargeLedger.included(w);
    if ((before < included * 8 / 10 && used >= included * 8 / 10)
        || (before < included && used >= included)) {
      String recipient =
          store.db.queryForObject(
              "SELECT email FROM users WHERE id=?", String.class, w.get("owner_id"));
      store.mail(
          recipient,
          "MeterTrail usage notice / 用量提醒",
          "Accepted units / 已接收 units: "
              + used
              + " / "
              + included
              + ". New objects stop at your configured quota. / 新增对象将在配置的额度上限处停止接收。");
    }
    try {
      store.db.update(
          "UPDATE ingest_batches SET accepted=?,duplicates=?,rejected=?,errors=?::jsonb WHERE id=?",
          accepted,
          duplicates,
          rejected,
          new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(errors),
          batch);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    metrics.counter("metertrail_events_accepted").increment(accepted);
    metrics.counter("metertrail_events_duplicates").increment(duplicates);
    return Map.of("batchId", batch, "received", b.path("events").size(), "status", "pending");
  }

  /**
   * The normal SDK/load path consists entirely of new identities. Persist that path in three
   * set-based statements instead of issuing several database round trips per event. Any replay,
   * revision, duplicate-in-envelope, quota edge, or usage notification crossing stays on the
   * established per-event path above.
   */
  private Map<String, Object> fastFresh(
      UUID project,
      UUID workspace,
      Map<String, Object> w,
      List<Map<String, Object>> packs,
      UUID batch,
      JsonNode body,
      long used,
      long overageUnits,
      long addonConsumed,
      long before) {
    var events = new ArrayList<JsonNode>();
    var eventIds = new ArrayList<UUID>();
    var identities = new ArrayList<String>();
    var eventSet = new HashSet<UUID>();
    var identitySet = new HashSet<String>();
    Instant now = Instant.now();
    String plan = (String) w.get("plan");
    Instant end = ((Timestamp) w.get("period_end")).toInstant();
    for (JsonNode event : body.path("events")) {
      try {
        Protocol.event(event);
        UUID eventId = Support.uuid(event.path("eventId").asText());
        String identity = event.path("type").asText() + "\u0000" + event.path("objectId").asText();
        Instant started = Instant.parse(event.path("startedAt").asText());
        if (!eventSet.add(eventId) || !identitySet.add(identity)) return null;
        if (started.isBefore(now.minus(Duration.ofDays(7))) || started.isAfter(now.plusSeconds(300)))
          return null;
        events.add(event);
        eventIds.add(eventId);
        identities.add(identity);
      } catch (RuntimeException invalid) {
        return null;
      }
    }
    if (events.isEmpty() || !end.isAfter(now) && !plan.equals("Free")) return null;
    long included = Billing.included(plan);
    if ((before < included * 8 / 10 && used + events.size() >= included * 8 / 10)
        || (before < included && used + events.size() >= included)) return null;
    String eventMarks = String.join(",", Collections.nCopies(eventIds.size(), "?"));
    var eventArgs = new ArrayList<Object>();
    eventArgs.add(project);
    eventArgs.addAll(eventIds);
    if (!store.db.queryForList(
            "SELECT 1 FROM ingest_events WHERE project_id=? AND event_id IN (" + eventMarks + ") LIMIT 1",
            eventArgs.toArray()).isEmpty()) return null;
    String identityWhere = String.join(" OR ", Collections.nCopies(identities.size(), "(type=? AND object_id=?)"));
    var identityArgs = new ArrayList<Object>();
    identityArgs.add(project);
    for (String identity : identities) {
      int split = identity.indexOf('\u0000');
      identityArgs.add(identity.substring(0, split));
      identityArgs.add(identity.substring(split + 1));
    }
    if (!store.db.queryForList(
            "SELECT 1 FROM billable_objects WHERE project_id=? AND (" + identityWhere + ") LIMIT 1",
            identityArgs.toArray()).isEmpty()) return null;

    Map<String, Object> pack = null;
    for (var candidate : packs)
      if (((Number) candidate.get("consumed_units")).longValue()
          < ((Number) candidate.get("units")).longValue()) { pack = candidate; break; }
    long baseRemaining = Math.max(0, included - (used - overageUnits - addonConsumed));
    long packRemaining = pack == null ? 0 : ((Number) pack.get("units")).longValue()
        - ((Number) pack.get("consumed_units")).longValue();
    if (events.size() > baseRemaining + packRemaining) return null;

    var ledger = new ArrayList<Object>();
    var objects = new ArrayList<Object>();
    var pending = new ArrayList<Object>();
    int index = 0;
    long packUsed = 0;
    for (JsonNode event : events) {
      String type = event.path("type").asText(), object = event.path("objectId").asText();
      var content = event.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) content).remove("eventId");
      UUID packId = index >= baseRemaining && pack != null ? (UUID) pack.get("id") : null;
      if (packId != null) packUsed++;
      Collections.addAll(ledger, workspace, project, type, object, "accepted", w.get("period_start"), false, packId);
      Collections.addAll(objects, project, type, object, event.path("revision").asLong(),
          Support.hash(Support.canonical(content)), batch);
      Collections.addAll(pending, project, eventIds.get(index), Support.hash(Support.canonical(event)),
          batch, index, event.toString(), false);
      index++;
    }
    multiInsert("INSERT INTO usage_ledger(workspace_id,project_id,type,object_id,units,reason,period_start,overage,addon_charge_id) VALUES ",
        "(?,?,?,?,1,?,?,?,?)", events.size(), ledger);
    multiInsert("INSERT INTO billable_objects(project_id,type,object_id,revision,content_hash,reservation_batch,charge_generation,charged) VALUES ",
        "(?,?,?,?,?,?,1,true)", events.size(), objects);
    multiInsert("INSERT INTO ingest_events(project_id,event_id,content_hash,batch_id,event_index,payload,processed) VALUES ",
        "(?,?,?,?,?,?::jsonb,?)", events.size(), pending);
    used += events.size();
    addonConsumed += packUsed;
    store.db.update("UPDATE workspaces SET used_units=?,addon_consumed=? WHERE id=?", used, addonConsumed, workspace);
    if (pack != null && packUsed > 0) {
      long consumed = ((Number) pack.get("consumed_units")).longValue() + packUsed;
      pack.put("consumed_units", consumed);
      store.db.update("UPDATE usage_charges SET consumed_units=? WHERE id=?", consumed, pack.get("id"));
    }
    store.db.update("UPDATE ingest_batches SET accepted=? WHERE id=?", events.size(), batch);
    metrics.counter("metertrail_events_accepted").increment(events.size());
    return Map.of("batchId", batch, "received", events.size(), "status", "pending");
  }

  private void multiInsert(String prefix, String row, int count, List<Object> args) {
    store.db.update(prefix + String.join(",", Collections.nCopies(count, row)), args.toArray());
  }

  private void remember(
      UUID project,
      UUID event,
      String hash,
      UUID batch,
      int index,
      JsonNode payload,
      boolean processed) {
    store.db.update(
        "INSERT INTO"
            + " ingest_events(project_id,event_id,content_hash,batch_id,event_index,payload,processed)"
            + " VALUES(?,?,?,?,?,?::jsonb,?)",
        project,
        event,
        hash,
        batch,
        index,
        payload.toString(),
        processed);
  }
}
