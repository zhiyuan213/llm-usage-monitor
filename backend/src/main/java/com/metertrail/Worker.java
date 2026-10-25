package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class Worker {
  final Store store;
  final TransactionTemplate tx;
  final MailDelivery mail;
  final boolean enabled;
  final String from;
  @Value("${metertrail.public-url}")
  String publicUrl="https://metertrail.com";

  @Value("${metertrail.worker.group-batches:100}")
  int groupBatches = 100;

  @Value("${metertrail.worker.group-events:500}")
  int groupEvents = 500;

  Worker(
      Store store,
      TransactionTemplate tx,
      MailDelivery mail,
      @Value("${metertrail.worker-enabled}") boolean enabled,
      @Value("${metertrail.mail-from}") String from) {
    this.store = store;
    this.tx = tx;
    this.mail = mail;
    this.enabled = enabled;
    this.from = from;
  }

  public void process() {
    drain(10, Long.MAX_VALUE);
  }

  /** Materialize bounded same-project groups; authoritative billing is already committed. */
  boolean drain(int maxBatches, long budgetNanos) {
    if (!enabled) return false;
    long sliceStarted = System.nanoTime();
    for (int done = 0; done < maxBatches; ) {
      if (Thread.currentThread().isInterrupted()) return false;
      if (done > 0 && System.nanoTime() - sliceStarted >= budgetNanos) return true;
      var claimed = new ArrayList<UUID>();
      int remaining = maxBatches - done;
      try {
        Boolean found =
            tx.execute(
                status -> {
                  var heads =
                      store
                          .queries()
                          .queryForList(
                              "SELECT * FROM ingest_batches WHERE status='pending' AND (lease_until"
                                  + " IS NULL OR lease_until<now()) ORDER BY created_at,id FOR"
                                  + " UPDATE SKIP LOCKED LIMIT 1");
                  if (heads.isEmpty()) return false;
                  var first = heads.get(0);
                  UUID batch = (UUID) first.get("id"), project = (UUID) first.get("project_id");
                  claimed.add(batch);
                  int eventsInGroup = ((Number) first.get("accepted")).intValue();
                  int limit = Math.max(1, Math.min(groupBatches, remaining));
                  if (((Number) first.get("attempts")).intValue() == 0 && limit > 1) {
                    var extra =
                        store
                            .queries()
                            .queryForList(
                                "SELECT id,accepted FROM ingest_batches WHERE project_id=? AND"
                                    + " id<>? AND status='pending' AND attempts=0 AND (lease_until"
                                    + " IS NULL OR lease_until<now()) ORDER BY created_at,id FOR"
                                    + " UPDATE SKIP LOCKED LIMIT ?",
                                project,
                                batch,
                                limit - 1);
                    for (var candidate : extra) {
                      int count = ((Number) candidate.get("accepted")).intValue();
                      if (eventsInGroup + count > groupEvents) break;
                      claimed.add((UUID) candidate.get("id"));
                      eventsInGroup += count;
                    }
                  }
                  String placeholders = String.join(",", Collections.nCopies(claimed.size(), "?"));
                  Object[] ids = claimed.toArray();
                  if (!store
                      .queries()
                      .queryForList(
                          "SELECT id FROM projects WHERE id=? AND deleted_at IS NOT NULL", project)
                      .isEmpty()) {
                    store.db.update(
                        "UPDATE ingest_events SET processed=true,payload='{}' WHERE batch_id IN ("
                            + placeholders
                            + ")",
                        ids);
                    store.db.update(
                        "UPDATE ingest_batches SET status='deleted',completed_at=now() WHERE id IN"
                            + " ("
                            + placeholders
                            + ")",
                        ids);
                    return true;
                  }
                  var events =
                      store
                          .queries()
                          .queryForList(
                              "SELECT * FROM ingest_events WHERE batch_id IN ("
                                  + placeholders
                                  + ") AND NOT processed ORDER BY batch_id,event_index",
                              ids);
                  var objectArgs = new ArrayList<Object>();
                  var costArgs = new ArrayList<Object>();
                  int costCount = 0;
                  for (var row : events) {
                    JsonNode e = Support.parse(row.get("payload").toString());
                    JsonNode d = e.path("data");
                    String type = e.path("type").asText();
                    BigDecimal cost = null;
                    UUID price = null;
                    Instant started = Instant.parse(e.path("startedAt").asText());
                    if (type.equals("observation")
                        && d.path("observationType").asText().equals("generation")) {
                      var prices =
                          store
                              .queries()
                              .queryForList(
                                  "SELECT * FROM price_versions WHERE project_id=? AND provider=?"
                                      + " AND model=? AND effective_at<=? ORDER BY effective_at"
                                      + " DESC LIMIT 1",
                                  project,
                                  d.path("provider").asText(),
                                  d.path("model").asText(),
                                  Timestamp.from(started));
                      JsonNode u = d.path("usage");
                      if (!prices.isEmpty()
                          && u.path("inputTokens").isNumber()
                          && u.path("outputTokens").isNumber()
                          && u.path("cachedInputTokens").isNumber()) {
                        var p = prices.get(0);
                        price = (UUID) p.get("id");
                        cost =
                            BigDecimal.valueOf(
                                    u.path("inputTokens").asLong()
                                        - u.path("cachedInputTokens").asLong())
                                .multiply((BigDecimal) p.get("input_rate"))
                                .add(
                                    BigDecimal.valueOf(u.path("outputTokens").asLong())
                                        .multiply((BigDecimal) p.get("output_rate")))
                                .add(
                                    BigDecimal.valueOf(u.path("cachedInputTokens").asLong())
                                        .multiply((BigDecimal) p.get("cached_rate")))
                                .divide(BigDecimal.valueOf(1_000_000));
                      }
                    }
                    Collections.addAll(
                        objectArgs,
                        project,
                        type,
                        e.path("objectId").asText(),
                        e.path("revision").asLong(),
                        e.path("traceId").asText(null),
                        e.path("spanId").asText(null),
                        e.path("parentSpanId").asText(null),
                        Timestamp.from(started),
                        d.toString(),
                        cost,
                        price);
                    if (type.equals("observation")) {
                      Collections.addAll(
                          costArgs,
                          project,
                          e.path("objectId").asText(),
                          e.path("revision").asLong(),
                          Timestamp.from(started),
                          d.has("usage") ? d.path("usage").toString() : null,
                          price,
                          cost);
                      costCount++;
                    }
                  }

                  // Multiple client batches may contain revisions of the same object. PostgreSQL
                  // ON CONFLICT DO UPDATE must see that identity only once in each INSERT.
                  var latest = new LinkedHashMap<List<Object>, List<Object>>();
                  for (int offset = 0; offset < objectArgs.size(); offset += 11) {
                    var values = new ArrayList<>(objectArgs.subList(offset, offset + 11));
                    var key = List.of(values.get(1), values.get(2));
                    var old = latest.get(key);
                    if (old == null
                        || ((Number) old.get(3)).longValue() < ((Number) values.get(3)).longValue())
                      latest.put(key, values);
                  }
                  objectArgs.clear();
                  latest.values().forEach(objectArgs::addAll);
                  if (!events.isEmpty()) {
                    store.db.update(
                        "INSERT INTO"
                            + " objects(project_id,type,object_id,revision,trace_id,span_id,parent_span_id,started_at,data,cost,price_version)"
                            + " VALUES "
                            + String.join(
                                ",",
                                Collections.nCopies(
                                    latest.size(), "(?,?,?,?,?,?,?,?,?::jsonb,?,?)"))
                            + " ON CONFLICT(project_id,type,object_id) DO UPDATE SET"
                            + " revision=excluded.revision,trace_id=excluded.trace_id,span_id=excluded.span_id,parent_span_id=excluded.parent_span_id,started_at=excluded.started_at,data=excluded.data,cost=excluded.cost,price_version=excluded.price_version"
                            + " WHERE objects.revision<excluded.revision",
                        objectArgs.toArray());
                    if (costCount > 0)
                      store.db.update(
                          "INSERT INTO"
                              + " cost_entries(project_id,object_id,revision,started_at,usage,price_version,estimated_cost)"
                              + " VALUES "
                              + String.join(
                                  ",", Collections.nCopies(costCount, "(?,?,?,?,?::jsonb,?,?)"))
                              + " ON CONFLICT DO NOTHING",
                          costArgs.toArray());
                    store.db.update(
                        "UPDATE ingest_events SET processed=true WHERE batch_id IN ("
                            + placeholders
                            + ")",
                        ids);
                  }
                  store.db.update(
                      "UPDATE ingest_batches SET status='completed',completed_at=now() WHERE id IN"
                          + " ("
                          + placeholders
                          + ")",
                      ids);
                  return true;
                });
        if (!Boolean.TRUE.equals(found)) return false;
      } catch (RuntimeException failure) {
        if (claimed.isEmpty()) throw failure;
        // A failed group rolls back entirely. Retried batches run alone so one poison
        // payload cannot repeatedly poison otherwise healthy members of the group.
        tx.executeWithoutResult(
            status -> {
              for (UUID batch : claimed) {
                store.db.update(
                    "UPDATE ingest_batches SET attempts=attempts+1,lease_until=CASE WHEN ? THEN"
                        + " now() ELSE now()+interval '30 seconds' END,status=CASE WHEN attempts>=9"
                        + " THEN 'quarantined' ELSE status END WHERE id=? AND status='pending'",
                    claimed.size() > 1,
                    batch);
                var rows =
                    store
                        .queries()
                        .queryForList(
                            "SELECT b.status,p.workspace_id FROM ingest_batches b JOIN projects p"
                                + " ON p.id=b.project_id WHERE b.id=?",
                            batch);
                if (!rows.isEmpty() && rows.get(0).get("status").equals("quarantined"))
                  store.audit(
                      null,
                      (UUID) rows.get(0).get("workspace_id"),
                      "batch.quarantined",
                      batch.toString(),
                      false,
                      null);
              }
            });
      }
      done += claimed.size();
    }
    return true;
  }

  @Scheduled(fixedDelay = 2000)
  public void notifications() {
    if (!enabled) return;
    tx.executeWithoutResult(
        s -> {
          var rows =
              store
                  .queries()
                  .queryForList(
                      "SELECT * FROM outbox WHERE sent_at IS NULL AND attempts<10 ORDER BY id FOR"
                          + " UPDATE SKIP LOCKED LIMIT 5");
          for (var row : rows) {
            try {
              if (!WeeklyInsights.mailEligible(store,row.get("id"))) {
                store.db.update("UPDATE outbox SET attempts=10,body='' WHERE id=?",row.get("id"));
                store.db.update("UPDATE insight_weekly_reports SET cancelled_at=now() WHERE outbox_id=?",row.get("id"));
                continue;
              }
              if (row.get("budget_project_id")!=null && !BudgetAlerts.mailEligible(store,row.get("id"))) {
                store.db.update("UPDATE outbox SET attempts=10,body='' WHERE id=?",row.get("id"));
                store.db.update("UPDATE budget_notifications SET cancelled_at=now() WHERE outbox_id=?",row.get("id"));
                continue;
              }
              SimpleMailMessage m = new SimpleMailMessage();
              m.setFrom(from);
              m.setTo((String) row.get("recipient"));
              m.setSubject((String) row.get("subject"));
              m.setText(store.secrets.open((String) row.get("body")));
              if(row.get("insight_project_id")!=null) {
                var report=store.queries().queryForMap("SELECT r.summary,p.name FROM insight_weekly_reports r JOIN projects p ON p.id=r.project_id WHERE r.outbox_id=?",row.get("id"));
                @SuppressWarnings("unchecked")
                Map<String,Object> summary=new com.fasterxml.jackson.databind.ObjectMapper().readValue(report.get("summary").toString(),Map.class);
                mail.sendWeekly(m,WeeklyEmail.values(report.get("name").toString(),summary,(UUID)row.get("insight_project_id"),publicUrl.replaceAll("/+$","")));
              } else mail.send(m);
              store.db.update("UPDATE outbox SET sent_at=now(),body='' WHERE id=?", row.get("id"));
            } catch (Exception e) {
              store.db.update("UPDATE outbox SET attempts=attempts+1 WHERE id=?", row.get("id"));
            }
          }
        });
  }

  @Scheduled(fixedDelay = 60000)
  public void alerts() {
    if (!enabled) return;
    tx.executeWithoutResult(
        s -> {
          var rules =
              store
                  .queries()
                  .queryForList(
                      "SELECT r.*,u.email,(r.last_triggered_at IS NULL OR"
                          + " r.last_triggered_at<now()-interval '1 hour') AS ready FROM"
                          + " alert_rules r JOIN projects p ON p.id=r.project_id JOIN workspaces w"
                          + " ON w.id=p.workspace_id JOIN users u ON u.id=w.owner_id WHERE"
                          + " r.enabled AND r.deleted_at IS NULL AND p.deleted_at IS NULL FOR"
                          + " UPDATE OF r SKIP LOCKED");
          for (var r : rules) {
            String metric = (String) r.get("metric");
            String value =
                switch (metric) {
                  case "latency_ms" -> "(o.data->>'durationMs')::numeric";
                  case "cost_usd" -> "o.cost";
                  default -> "1::numeric";
                };
            String qualifies =
                switch (metric) {
                  case "latency_ms" ->
                      "(o.data->>'durationMs')::numeric>="
                          + ((BigDecimal) r.get("threshold")).toPlainString();
                  case "cost_usd" -> "o.cost>0";
                  default -> "o.data->>'status'='error'";
                };
            String aggregate = metric.equals("latency_ms") ? "max" : "sum";
            String window =
                " FROM objects o WHERE o.project_id=? AND o.started_at>now()-interval '1 hour' AND"
                    + " o.started_at<=now() AND ("
                    + qualifies
                    + ")";
            BigDecimal actual =
                store
                    .queries()
                    .queryForObject(
                        "SELECT coalesce(" + aggregate + "(" + value + "),0)" + window,
                        BigDecimal.class,
                        r.get("project_id"));
            if (actual.compareTo((BigDecimal) r.get("threshold")) >= 0) {
              // Capture new evidence even during cooldown, so it survives expiry of the lookback
              // window.
              store.db.update(
                  "INSERT INTO"
                      + " alert_evidence(rule_id,type,object_id,revision,value,started_at,window_value)"
                      + " SELECT ?,o.type,o.object_id,o.revision,"
                      + value
                      + ",o.started_at,?::numeric"
                      + window
                      + " ON CONFLICT(rule_id,type,object_id) DO UPDATE SET"
                      + " revision=excluded.revision,value=excluded.value,started_at=excluded.started_at,window_value=excluded.window_value,notified_at=NULL"
                      + " WHERE excluded.revision>alert_evidence.revision",
                  r.get("id"),
                  actual,
                  r.get("project_id"));
            }
            if (!Boolean.TRUE.equals(r.get("ready"))) continue;
            var pending =
                store
                    .queries()
                    .queryForMap(
                        "SELECT max(window_value) AS window_value,count(*) AS count,"
                            + aggregate
                            + "(value) AS value,min(started_at) AS first_at,max(started_at) AS"
                            + " last_at FROM alert_evidence WHERE rule_id=? AND notified_at IS"
                            + " NULL",
                        r.get("id"));
            long count = ((Number) pending.get("count")).longValue();
            if (count == 0) continue;
            store.db.update(
                "INSERT INTO"
                    + " alert_events(rule_id,value,evidence_count,first_started_at,last_started_at)"
                    + " VALUES(?,?,?,?,?)",
                r.get("id"),
                pending.get("window_value"),
                count,
                pending.get("first_at"),
                pending.get("last_at"));
            store.db.update(
                "UPDATE alert_evidence SET notified_at=now() WHERE rule_id=? AND notified_at IS"
                    + " NULL",
                r.get("id"));
            store.db.update(
                "UPDATE alert_rules SET last_triggered_at=now() WHERE id=?", r.get("id"));
            store.mail(
                (String) r.get("email"),
                "MeterTrail alert / 告警",
                r.get("name")
                    + ": "
                    + pending.get("window_value")
                    + "\nMetric / 指标: "
                    + metric
                    + "\nThreshold / 阈值: "
                    + r.get("threshold")
                    + "\nNew matching objects / 新增匹配对象数: "
                    + count
                    + "\nNew evidence contribution / 新匹配数据指标值: "
                    + pending.get("value")
                    + "\nEvent time range / 事件时间范围 (UTC): "
                    + ((Timestamp) pending.get("first_at")).toInstant()
                    + " — "
                    + ((Timestamp) pending.get("last_at")).toInstant()
                    + "\n"
                    + "Cooldown-period matches are merged; previously notified revisions are not"
                    + " sent again.\n"
                    + "冷却期间的新匹配数据合并通知，已通知的对象版本不再重复提醒。");
          }
        });
  }

  @Scheduled(fixedDelay = 3600000)
  public void retention() {
    if (!enabled) return;
    store.db.update("DELETE FROM sessions WHERE expires_at<now()");
    store.db.update("DELETE FROM verification_challenges WHERE expires_at<now()-interval '1 day'");
    store.db.update(
        "DELETE FROM objects o USING projects p,workspaces w WHERE o.project_id=p.id AND"
            + " p.workspace_id=w.id AND (p.deleted_at IS NOT NULL OR"
            + " o.started_at<now()-make_interval(days=>CASE w.plan WHEN 'Pro' THEN 1096 WHEN 'Core'"
            + " THEN 90 ELSE 30 END))");
    store.db.update(
        "DELETE FROM alert_evidence e USING alert_rules r WHERE e.rule_id=r.id AND (EXISTS(SELECT 1"
            + " FROM projects p WHERE p.id=r.project_id AND p.deleted_at IS NOT NULL) OR"
            + " (e.notified_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM objects o WHERE"
            + " o.project_id=r.project_id AND o.type=e.type AND o.object_id=e.object_id)))");
    store.db.update(
        "DELETE FROM cost_entries c USING projects p,workspaces w WHERE c.project_id=p.id AND"
            + " p.workspace_id=w.id AND (p.deleted_at IS NOT NULL OR"
            + " c.started_at<now()-make_interval(days=>CASE w.plan WHEN 'Pro' THEN 1096 WHEN 'Core'"
            + " THEN 90 ELSE 30 END))");
    store.db.update(
        "UPDATE ingest_events e SET payload='{}' FROM projects p WHERE e.project_id=p.id AND"
            + " p.deleted_at IS NOT NULL AND e.processed");
    store.db.update(
        "DELETE FROM ingest_events WHERE created_at<now()-interval '8 days' AND processed");
    store.db.update(
        "DELETE FROM ingest_batches b WHERE b.created_at<now()-interval '8 days' AND"
            + " b.status<>'pending' AND NOT EXISTS(SELECT 1 FROM ingest_events e WHERE"
            + " e.batch_id=b.id) AND NOT EXISTS(SELECT 1 FROM billable_objects o WHERE"
            + " o.reservation_batch=b.id)");
  }

  @Scheduled(fixedDelay = 60000)
  public void freePeriods() {
    if (!enabled) return;
    tx.executeWithoutResult(
        s -> {
          var rows =
              store
                  .queries()
                  .queryForList(
                      "UPDATE workspaces SET"
                          + " used_units=0,period_start=date_trunc('month',now()),period_end=date_trunc('month',now())+interval"
                          + " '1 month' WHERE plan='Free' AND period_end<=now() RETURNING id");
          for (var row : rows)
            store.audit(
                null,
                (UUID) row.get("id"),
                "billing.free-period",
                "UTC calendar month",
                true,
                null);
        });
  }
}
