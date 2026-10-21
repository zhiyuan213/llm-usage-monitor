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

  @Scheduled(fixedDelay = 300)
  public void process() {
    if (!enabled) return;
    for (int i = 0; i < 10; i++) {
      UUID[] claimed = {null};
      Boolean found;
      try {
        found =
            tx.execute(
                s -> {
                  var batches =
                      store.db.queryForList(
                          "SELECT * FROM ingest_batches WHERE status='pending' AND (lease_until IS"
                              + " NULL OR lease_until<now()) ORDER BY created_at FOR UPDATE SKIP"
                              + " LOCKED LIMIT 1");
                  if (batches.isEmpty()) return false;
                  var b = batches.get(0);
                  UUID batch = (UUID) b.get("id"), project = (UUID) b.get("project_id");
                  claimed[0] = batch;
                  // Transaction row lock is the lease: connection loss rolls back and immediately
                  // releases it.
                  if (!store
                      .db
                      .queryForList(
                          "SELECT id FROM projects WHERE id=? AND deleted_at IS NOT NULL", project)
                      .isEmpty()) {
                    store.db.update(
                        "UPDATE ingest_events SET processed=true,payload='{}' WHERE batch_id=?",
                        batch);
                    store.db.update(
                        "UPDATE ingest_batches SET status='deleted',completed_at=now() WHERE id=?",
                        batch);
                    return true;
                  }
                  var events =
                      store.db.queryForList(
                          "SELECT * FROM ingest_events WHERE batch_id=? AND NOT processed ORDER BY"
                              + " event_index",
                          batch);
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
                          store.db.queryForList(
                              "SELECT * FROM price_versions WHERE project_id=? AND provider=? AND"
                                  + " model=? AND effective_at<=? ORDER BY effective_at DESC LIMIT"
                                  + " 1",
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
                    store.db.update(
                        "INSERT INTO"
                            + " objects(project_id,type,object_id,revision,trace_id,span_id,parent_span_id,started_at,data,cost,price_version)"
                            + " VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,?) ON"
                            + " CONFLICT(project_id,type,object_id) DO UPDATE SET"
                            + " revision=excluded.revision,trace_id=excluded.trace_id,span_id=excluded.span_id,parent_span_id=excluded.parent_span_id,started_at=excluded.started_at,data=excluded.data,cost=excluded.cost,price_version=excluded.price_version"
                            + " WHERE objects.revision<excluded.revision",
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
                    store.db.update(
                        "INSERT INTO"
                            + " cost_entries(project_id,object_id,revision,started_at,usage,price_version,estimated_cost)"
                            + " SELECT ?,?,?,?,?::jsonb,?,? WHERE ?='observation' ON CONFLICT DO"
                            + " NOTHING",
                        project,
                        e.path("objectId").asText(),
                        e.path("revision").asLong(),
                        Timestamp.from(started),
                        d.has("usage") ? d.path("usage").toString() : null,
                        price,
                        cost,
                        type);
                    store.db.update(
                        "UPDATE ingest_events SET processed=true WHERE project_id=? AND event_id=?",
                        project,
                        row.get("event_id"));
                  }
                  store.db.update(
                      "UPDATE ingest_batches SET status='completed',completed_at=now() WHERE id=?",
                      batch);
                  return true;
                });
      } catch (RuntimeException failure) {
        if (claimed[0] == null) throw failure;
        tx.executeWithoutResult(
            s -> {
              store.db.update(
                  "UPDATE ingest_batches SET attempts=attempts+1,lease_until=now()+interval '30"
                      + " seconds',status=CASE WHEN attempts>=9 THEN 'quarantined' ELSE status END"
                      + " WHERE id=? AND status='pending'",
                  claimed[0]);
              var rows =
                  store.db.queryForList(
                      "SELECT b.status,p.workspace_id FROM ingest_batches b JOIN projects p ON"
                          + " p.id=b.project_id WHERE b.id=?",
                      claimed[0]);
              if (!rows.isEmpty() && rows.get(0).get("status").equals("quarantined"))
                store.audit(
                    null,
                    (UUID) rows.get(0).get("workspace_id"),
                    "batch.quarantined",
                    claimed[0].toString(),
                    false,
                    null);
            });
        continue;
      }
      if (!Boolean.TRUE.equals(found)) break;
    }
  }

  @Scheduled(fixedDelay = 2000)
  public void notifications() {
    if (!enabled) return;
    tx.executeWithoutResult(
        s -> {
          var rows =
              store.db.queryForList(
                  "SELECT * FROM outbox WHERE sent_at IS NULL AND attempts<10 ORDER BY id FOR"
                      + " UPDATE SKIP LOCKED LIMIT 5");
          for (var row : rows) {
            try {
              SimpleMailMessage m = new SimpleMailMessage();
              m.setFrom(from);
              m.setTo((String) row.get("recipient"));
              m.setSubject((String) row.get("subject"));
              m.setText(store.secrets.open((String) row.get("body")));
              mail.send(m);
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
              store.db.queryForList(
                  "SELECT r.*,u.email,(r.last_triggered_at IS NULL OR"
                      + " r.last_triggered_at<now()-interval '1 hour') AS ready FROM alert_rules r"
                      + " JOIN projects p ON p.id=r.project_id JOIN workspaces w ON"
                      + " w.id=p.workspace_id JOIN users u ON u.id=w.owner_id WHERE r.enabled AND"
                      + " p.deleted_at IS NULL FOR UPDATE OF r SKIP LOCKED");
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
                store.db.queryForObject(
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
                store.db.queryForMap(
                    "SELECT max(window_value) AS window_value,count(*) AS count,"
                        + aggregate
                        + "(value) AS value,min(started_at) AS first_at,max(started_at) AS last_at"
                        + " FROM alert_evidence WHERE rule_id=? AND notified_at IS NULL",
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
              store.db.queryForList(
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
