package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class IngestService {
  @org.springframework.beans.factory.annotation.Value(
      "${metertrail.usage-collection-enabled:false}")
  boolean collectionEnabled;

  final Store store;
  final MeterRegistry metrics;
  final TransactionTemplate transactions;
  final Map<String, io.micrometer.core.instrument.Timer> phaseTimers = new HashMap<>();

  IngestService(Store store, MeterRegistry metrics, PlatformTransactionManager transactions) {
    this.store = store;
    this.metrics = metrics;
    this.transactions = new TransactionTemplate(transactions);
    for (String phase :
        List.of(
            "prepare",
            "transaction_acquire",
            "transaction_body",
            "transaction_completion",
            "total",
            "workspace_lock_acquire",
            "workspace_lock_held",
            "quota_lookup",
            "overage_lookup",
            "event_identity_lookup",
            "object_identity_lookup",
            "ledger_insert",
            "identity_insert",
            "event_insert")) {
      phaseTimers.put(
          phase,
          io.micrometer.core.instrument.Timer.builder("metertrail_ingest_phase")
              .tag("phase", phase)
              .publishPercentileHistogram()
              .register(metrics));
    }
  }

  public Map<String, Object> accept(UUID project, JsonNode b) {
    long started = System.nanoTime();
    try {
      var normalized = Protocol.defaults(b);
      var prepared = prepare(normalized);
      elapsed("prepare", started);
      long acquiring = System.nanoTime();
      return transactions.execute(
          status -> {
            elapsed("transaction_acquire", acquiring);
            long bodyStarted = System.nanoTime();
            try {
              return acceptPrepared(project, normalized, prepared);
            } finally {
              elapsed("transaction_body", bodyStarted);
              long completing = System.nanoTime();
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int completionStatus) {
                      elapsed("transaction_completion", completing);
                    }
                  });
            }
          });
    } finally {
      elapsed("total", started);
    }
  }

  record PreparedEvent(
      JsonNode payload,
      String eventId,
      String eventHash,
      String contentHash,
      Instant started,
      String json,
      ApiError error,
      UUID originalBatch,
      int originalIndex) {
    PreparedEvent route(UUID batch, int index) {
      return new PreparedEvent(
          payload, eventId, eventHash, contentHash, started, json, error, batch, index);
    }

    UUID batch(UUID fallback) {
      return originalBatch == null ? fallback : originalBatch;
    }

    int index(int fallback) {
      return originalBatch == null ? fallback : originalIndex;
    }
  }

  List<PreparedEvent> prepare(JsonNode body) {
    var result = new ArrayList<PreparedEvent>();
    for (JsonNode event : body.path("events")) {
      try {
        Protocol.event(event);
        event = Protocol.eventDefaults(event);
        var content = event.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) content).remove("eventId");
        result.add(
            new PreparedEvent(
                event,
                Protocol.eventIdentity(event.path("eventId").asText()),
                Support.hash(Support.canonical(event)),
                Support.hash(Support.canonical(content)),
                Instant.parse(event.path("startedAt").asText()),
                event.toString(),
                null,
                null,
                -1));
      } catch (ApiError invalid) {
        // Preserve per-index rejection and partial acceptance in the transactional path.
        result.add(new PreparedEvent(event, null, null, null, null, null, invalid, null, -1));
      }
    }
    return result;
  }

  private void elapsed(String phase, long started) {
    phaseTimers
        .get(phase)
        .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
  }

  private Map<String, Object> acceptPrepared(
      UUID project, JsonNode b, List<PreparedEvent> prepared) {
    return acceptPrepared(project, b, prepared, false, null);
  }

  record EventOutcome(int index, String status, String code) {}

  private void note(List<EventOutcome> outcomes, int index, String status, String code) {
    if (outcomes != null) outcomes.add(new EventOutcome(index, status, code));
  }

  Map<String, Object> acceptPrepared(
      UUID project,
      JsonNode b,
      List<PreparedEvent> prepared,
      boolean workspaceLocked,
      List<EventOutcome> outcomes) {
    // Serialize by workspace before touching identity rows; all projects share this quota.
    long locking = System.nanoTime();
    var w =
        store
            .queries()
            .queryForMap(
                "SELECT w.* FROM workspaces w JOIN projects p ON p.workspace_id=w.id WHERE p.id=?"
                    + " AND p.deleted_at IS NULL"
                    + (workspaceLocked ? "" : " FOR UPDATE OF w"),
                project);
    if (!workspaceLocked) elapsed("workspace_lock_acquire", locking);
    long locked = System.nanoTime();
    if (!workspaceLocked)
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              elapsed("workspace_lock_held", locked);
            }
          });
    UUID workspace = (UUID) w.get("id");
    String plan = (String) w.get("plan");
    long used = ((Number) w.get("used_units")).longValue();
    long addonConsumed = ((Number) w.get("addon_consumed")).longValue();
    long before = used;
    long overageUnits = ((Number) w.get("overage_units")).longValue();
    Instant end = ((Timestamp) w.get("period_end")).toInstant();
    if (!end.isAfter(Instant.now()) && plan.equals("Free")) {
      store.db.update(
          "UPDATE workspaces SET"
              + " used_units=0,period_start=date_trunc('month',now()),period_end=date_trunc('month',now())+interval"
              + " '1 month' WHERE id=?",
          workspace);
      used = 0;
      w = store.queries().queryForMap("SELECT * FROM workspaces WHERE id=?", workspace);
      before = 0;
    }
    long quotaStarted = System.nanoTime();
    List<Map<String, Object>> packs =
        ((Number) w.get("addon_units")).longValue() > addonConsumed
            ? store
                .queries()
                .queryForList(
                    "SELECT id,units,consumed_units FROM usage_charges WHERE workspace_id=? AND"
                        + " period_start=? AND kind='addon' AND credited AND NOT revoked AND"
                        + " status='paid' AND consumed_units<units ORDER BY created_at,id",
                    workspace,
                    w.get("period_start"))
            : List.of();
    Map<UUID, Long> originalPackConsumption = new HashMap<>();
    for (var pack : packs)
      originalPackConsumption.put(
          (UUID) pack.get("id"), ((Number) pack.get("consumed_units")).longValue());
    elapsed("quota_lookup", quotaStarted);
    Boolean collectionBlocked = null;
    UUID batch = UUID.randomUUID();
    store.db.update(
        "INSERT INTO ingest_batches(id,project_id,agent,received) VALUES(?,?,?::jsonb,?)",
        batch,
        project,
        b.path("agent").toString(),
        b.path("events").size());
    var fast =
        fastFresh(
            project,
            workspace,
            w,
            packs,
            batch,
            prepared,
            used,
            overageUnits,
            addonConsumed,
            before);
    if (fast != null) {
      for (int i = 0; i < prepared.size(); i++) note(outcomes, i, "accepted", null);
      return fast;
    }
    int accepted = 0, duplicates = 0, rejected = 0, index = 0;
    List<Map<String, Object>> errors = new ArrayList<>();
    for (PreparedEvent p : prepared) {
      JsonNode e = p.payload();
      try {
        if (p.error() != null) throw p.error();
        String eventHash = p.eventHash();
        String eventId = p.eventId();
        var previous =
            store
                .queries()
                .queryForList(
                    "SELECT content_hash FROM ingest_events WHERE project_id=? AND event_key=?",
                    project,
                    eventId);
        if (!previous.isEmpty()) {
          if (!eventHash.equals(previous.get(0).get("content_hash")))
            throw new ApiError(400, "EVENT_ID_CONFLICT");
          duplicates++;
          note(outcomes, index, "duplicate", null);
          index++;
          continue;
        }
        String type = e.path("type").asText(), object = e.path("objectId").asText();
        long revision = e.path("revision").asLong();
        String contentHash = p.contentHash();
        var identities =
            store
                .queries()
                .queryForList(
                    "SELECT * FROM billable_objects WHERE project_id=? AND type=? AND object_id=?",
                    project,
                    type,
                    object);
        Instant started = p.started();
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
            remember(project, eventId, eventHash, p.batch(batch), p.index(index), p.json(), true);
            duplicates++;
            note(outcomes, index, "duplicate", "STALE_REVISION");
            index++;
            continue;
          }
          if (revision == rev) {
            if (!contentHash.equals(old.get("content_hash")))
              throw new ApiError(400, "REVISION_CONFLICT");
            remember(project, eventId, eventHash, p.batch(batch), p.index(index), p.json(), true);
            duplicates++;
            note(outcomes, index, "duplicate", null);
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
          boolean baseAvailable = used - overageUnits - addonConsumed < Billing.included(plan);
          Map<String, Object> pack = null;
          if (!baseAvailable)
            for (var candidate : packs)
              if (((Number) candidate.get("consumed_units")).longValue()
                  < ((Number) candidate.get("units")).longValue()) {
                pack = candidate;
                break;
              }
          boolean overage = !baseAvailable && pack == null;
          if (overage
              && !plan.equals("Free")
              && collectionEnabled
              && (boolean) w.get("auto_overage")
              && collectionBlocked == null) {
            long lookup = System.nanoTime();
            collectionBlocked =
                !store
                    .queries()
                    .queryForList(
                        "SELECT id FROM usage_charges WHERE workspace_id=? AND kind='overage' AND"
                            + " amount>0 AND status IN"
                            + " ('failed','unknown','processing','submitted') LIMIT 1",
                        workspace)
                    .isEmpty();
            elapsed("overage_lookup", lookup);
          }
          boolean allowed =
              !overage
                  || (!plan.equals("Free")
                      && collectionEnabled
                      && Boolean.FALSE.equals(collectionBlocked)
                      && (boolean) w.get("auto_overage")
                      && Billing.overage(Math.addExact(100001, overageUnits))
                              .compareTo((BigDecimal) w.get("overage_cap"))
                          <= 0);
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
              pack == null ? null : pack.get("id"));
          if (overage) overageUnits++;
          if (pack != null) {
            addonConsumed++;
            pack.put("consumed_units", ((Number) pack.get("consumed_units")).longValue() + 1);
          }
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
            p.batch(batch),
            generation);
        remember(project, eventId, eventHash, p.batch(batch), p.index(index), p.json(), false);
        accepted++;
        note(outcomes, index, "accepted", null);
      } catch (ApiError err) {
        rejected++;
        note(outcomes, index, "rejected", err.code);
        errors.add(Map.of("index", index, "code", err.code));
        metrics.counter("metertrail_events_rejected", "reason", err.code).increment();
      }
      index++;
    }
    if (accepted == 0
        && duplicates == 0
        && errors.stream().anyMatch(e -> e.get("code").equals("QUOTA_EXCEEDED")))
      throw new ApiError(429, "QUOTA_EXCEEDED");
    if (used != before
        || overageUnits != ((Number) w.get("overage_units")).longValue()
        || addonConsumed != ((Number) w.get("addon_consumed")).longValue()) {
      store.db.update(
          "UPDATE workspaces SET used_units=?,overage_units=?,addon_consumed=? WHERE id=?",
          used,
          overageUnits,
          addonConsumed,
          workspace);
    }
    for (var pack : packs)
      if (((Number) pack.get("consumed_units")).longValue()
          != originalPackConsumption.get((UUID) pack.get("id")))
        store.db.update(
            "UPDATE usage_charges SET consumed_units=? WHERE id=?",
            pack.get("consumed_units"),
            pack.get("id"));
    long included = ChargeLedger.included(w);
    if ((before < included * 8 / 10 && used >= included * 8 / 10)
        || (before < included && used >= included)) {
      String recipient =
          store
              .queries()
              .queryForObject(
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
      List<PreparedEvent> prepared,
      long used,
      long overageUnits,
      long addonConsumed,
      long before) {
    var events = new ArrayList<JsonNode>();
    var eventIds = new ArrayList<String>();
    var identities = new ArrayList<String>();
    var eventSet = new HashSet<String>();
    var identitySet = new HashSet<String>();
    Instant now = Instant.now();
    String plan = (String) w.get("plan");
    Instant end = ((Timestamp) w.get("period_end")).toInstant();
    for (PreparedEvent p : prepared) {
      JsonNode event = p.payload();
      try {
        if (p.error() != null) return null;
        String eventId = p.eventId();
        String identity = event.path("type").asText() + "\u0000" + event.path("objectId").asText();
        Instant started = p.started();
        if (!eventSet.add(eventId) || !identitySet.add(identity)) return null;
        if (started.isBefore(now.minus(Duration.ofDays(7)))
            || started.isAfter(now.plusSeconds(300))) return null;
        events.add(event);
        eventIds.add(eventId);
        identities.add(identity);
      } catch (RuntimeException invalid) {
        return null;
      }
    }
    if (events.isEmpty()
        || (!plan.equals("Free")
            && (!end.isAfter(now) || !"active".equals(w.get("subscription_status"))))) return null;
    long included = Billing.included(plan);
    if ((before < included * 8 / 10 && used + events.size() >= included * 8 / 10)
        || (before < included && used + events.size() >= included)) return null;
    String eventMarks = String.join(",", Collections.nCopies(eventIds.size(), "?"));
    var eventArgs = new ArrayList<Object>();
    eventArgs.add(project);
    eventArgs.addAll(eventIds);
    long eventLookup = System.nanoTime();
    var previousEvents =
        store
            .queries()
            .queryForList(
                "SELECT 1 FROM ingest_events WHERE project_id=? AND event_key IN ("
                    + eventMarks
                    + ") LIMIT 1",
                eventArgs.toArray());
    elapsed("event_identity_lookup", eventLookup);
    if (!previousEvents.isEmpty()) return null;
    var identitiesByType = new LinkedHashMap<String, List<String>>();
    for (String identity : identities) {
      int split = identity.indexOf('\u0000');
      identitiesByType
          .computeIfAbsent(identity.substring(0, split), ignored -> new ArrayList<>())
          .add(identity.substring(split + 1));
    }
    var identityClauses = new ArrayList<String>();
    var identityArgs = new ArrayList<Object>();
    identityArgs.add(project);
    for (var group : identitiesByType.entrySet()) {
      identityClauses.add(
          "(type=? AND object_id IN ("
              + String.join(",", Collections.nCopies(group.getValue().size(), "?"))
              + "))");
      identityArgs.add(group.getKey());
      identityArgs.addAll(group.getValue());
    }
    long objectLookup = System.nanoTime();
    var previousObjects =
        store
            .queries()
            .queryForList(
                "SELECT 1 FROM billable_objects WHERE project_id=? AND ("
                    + String.join(" OR ", identityClauses)
                    + ") LIMIT 1",
                identityArgs.toArray());
    elapsed("object_identity_lookup", objectLookup);
    if (!previousObjects.isEmpty()) return null;

    Map<String, Object> pack = null;
    for (var candidate : packs)
      if (((Number) candidate.get("consumed_units")).longValue()
          < ((Number) candidate.get("units")).longValue()) {
        pack = candidate;
        break;
      }
    long baseRemaining = Math.max(0, included - (used - overageUnits - addonConsumed));
    long packRemaining =
        pack == null
            ? 0
            : ((Number) pack.get("units")).longValue()
                - ((Number) pack.get("consumed_units")).longValue();
    if (events.size() > baseRemaining + packRemaining) return null;

    var ledger = new ArrayList<Object>();
    var objects = new ArrayList<Object>();
    var pending = new ArrayList<Object>();
    int index = 0;
    long packUsed = 0;
    for (JsonNode event : events) {
      String type = event.path("type").asText(), object = event.path("objectId").asText();
      PreparedEvent p = prepared.get(index);
      UUID packId = index >= baseRemaining && pack != null ? (UUID) pack.get("id") : null;
      if (packId != null) packUsed++;
      Collections.addAll(
          ledger,
          workspace,
          project,
          type,
          object,
          "accepted",
          w.get("period_start"),
          false,
          packId);
      Collections.addAll(
          objects,
          project,
          type,
          object,
          event.path("revision").asLong(),
          p.contentHash(),
          p.batch(batch));
      Collections.addAll(
          pending,
          project,
          Protocol.legacyEventIdentity(eventIds.get(index)),
          eventIds.get(index),
          p.eventHash(),
          p.batch(batch),
          p.index(index),
          p.json(),
          false);
      index++;
    }
    multiInsert(
        "INSERT INTO"
            + " usage_ledger(workspace_id,project_id,type,object_id,units,reason,period_start,overage,addon_charge_id)"
            + " VALUES ",
        "(?,?,?,?,1,?,?,?,?)",
        events.size(),
        ledger);
    multiInsert(
        "INSERT INTO"
            + " billable_objects(project_id,type,object_id,revision,content_hash,reservation_batch,charge_generation,charged)"
            + " VALUES ",
        "(?,?,?,?,?,?,1,true)",
        events.size(),
        objects);
    multiInsert(
        "INSERT INTO"
            + " ingest_events(project_id,event_id,event_key,content_hash,batch_id,event_index,payload,processed)"
            + " VALUES ",
        "(?,?,?,?,?,?,?::jsonb,?)",
        events.size(),
        pending);
    used += events.size();
    addonConsumed += packUsed;
    store.db.update(
        "UPDATE workspaces SET used_units=?,addon_consumed=? WHERE id=?",
        used,
        addonConsumed,
        workspace);
    if (pack != null && packUsed > 0) {
      long consumed = ((Number) pack.get("consumed_units")).longValue() + packUsed;
      pack.put("consumed_units", consumed);
      store.db.update(
          "UPDATE usage_charges SET consumed_units=? WHERE id=?", consumed, pack.get("id"));
    }
    store.db.update("UPDATE ingest_batches SET accepted=? WHERE id=?", events.size(), batch);
    metrics.counter("metertrail_events_accepted").increment(events.size());
    return Map.of("batchId", batch, "received", events.size(), "status", "pending");
  }

  private void multiInsert(String prefix, String row, int count, List<Object> args) {
    long started = System.nanoTime();
    try {
      store.db.update(prefix + String.join(",", Collections.nCopies(count, row)), args.toArray());
    } finally {
      String phase =
          prefix.contains("usage_ledger")
              ? "ledger_insert"
              : prefix.contains("billable_objects") ? "identity_insert" : "event_insert";
      elapsed(phase, started);
    }
  }

  private void remember(
      UUID project,
      String event,
      String hash,
      UUID batch,
      int index,
      String payload,
      boolean processed) {
    store.db.update(
        "INSERT INTO"
            + " ingest_events(project_id,event_id,event_key,content_hash,batch_id,event_index,payload,processed)"
            + " VALUES(?,?,?,?,?,?,?::jsonb,?)",
        project,
        Protocol.legacyEventIdentity(event),
        event,
        hash,
        batch,
        index,
        payload,
        processed);
  }
}
