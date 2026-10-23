package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class ConsoleController {
  @GetMapping("/api/projects/{project}/batches")
  List<Map<String, Object>> batches(@PathVariable UUID project, HttpServletRequest req) {
    store.project(uid(req), project);
    var rows =
        store.db.queryForList(
            "SELECT id,status,received,accepted,duplicates,rejected,errors,attempts,created_at FROM"
                + " ingest_batches WHERE project_id=? ORDER BY created_at DESC LIMIT 50",
            project);
    for (var r : rows) r.put("errors", Support.parse(r.get("errors").toString()));
    return rows;
  }

  @PostMapping("/api/projects/{project}/batches/{batch}/replay")
  @Transactional
  Map<String, String> replay(
      @PathVariable UUID project, @PathVariable UUID batch, HttpServletRequest req) {
    var p = store.project(uid(req), project);
    int changed =
        store.db.update(
            "UPDATE ingest_batches SET status='pending',attempts=0,lease_until=NULL WHERE id=? AND"
                + " project_id=? AND status='quarantined'",
            batch,
            project);
    if (changed == 0) throw new ApiError(409, "BATCH_NOT_QUARANTINED");
    store.audit(
        uid(req), (UUID) p.get("workspace_id"), "batch.replay", batch.toString(), true, req);
    return Map.of("status", "pending");
  }

  final Store store;
  final Challenges challenges;
  final RateLimits limits;

  ConsoleController(Store store, Challenges challenges, RateLimits limits) {
    this.store = store;
    this.challenges = challenges;
    this.limits = limits;
  }

  UUID uid(HttpServletRequest req) {
    return SessionFilter.uid(req);
  }

  @GetMapping("/api/projects")
  List<Map<String, Object>> projects(HttpServletRequest req) {
    return store.db.queryForList(
        "SELECT p.*,(SELECT count(*) FROM objects o WHERE o.project_id=p.id) AS object_count FROM"
            + " projects p JOIN workspaces w ON w.id=p.workspace_id WHERE w.owner_id=? AND"
            + " p.deleted_at IS NULL ORDER BY p.created_at",
        uid(req));
  }

  @PostMapping("/api/projects")
  @Transactional
  Map<String, Object> create(@RequestBody JsonNode b, HttpServletRequest req) {
    UUID w = (UUID) store.workspace(uid(req)).get("id"), id = UUID.randomUUID();
    String name = Support.text(b, "name", 128), env = Support.text(b, "environment", 128);
    store.db.update(
        "INSERT INTO projects(id,workspace_id,name,environment) VALUES(?,?,?,?)", id, w, name, env);
    store.audit(uid(req), w, "project.create", id.toString(), true, req);
    return Map.of("id", id, "name", name, "environment", env);
  }

  @PostMapping("/api/verification")
  Map<String, Object> verification(@RequestBody JsonNode b, HttpServletRequest req) {
    limits.check("verify-send:" + req.getRemoteAddr(), 10);
    String action = b.path("action").asText(), target = Support.text(b, "target", 512);
    Support.require(
        Set.of(
                "key.create",
                "key.revoke",
                "project.delete",
                "billing.overage",
                "github.link",
                "github.unlink",
                "account.delete",
                "batch.cancel")
            .contains(action),
        "INVALID_ACTION");
    return Map.of("challengeId", challenges.issue(uid(req), action, target, req));
  }

  void verify(JsonNode b, String action, String target, HttpServletRequest req) {
    challenges.consume(
        uid(req), action, target, b.path("challengeId").asText(), b.path("code").asText(), req);
  }

  @GetMapping("/api/projects/{project}/keys")
  List<Map<String, Object>> keys(@PathVariable UUID project, HttpServletRequest req) {
    store.project(uid(req), project);
    return store.db.queryForList(
        "SELECT id,name,prefix,created_at,revoked_at FROM api_keys WHERE project_id=? AND"
            + " deleted_at IS NULL ORDER BY created_at DESC",
        project);
  }

  @PostMapping("/api/projects/{project}/keys")
  @Transactional
  Map<String, Object> key(
      @PathVariable UUID project, @RequestBody JsonNode b, HttpServletRequest req) {
    var p = store.project(uid(req), project);
    String name = Support.text(b, "name", 128);
    verify(b, "key.create", project + ":" + name, req);
    UUID id = UUID.randomUUID();
    String key = "lm_live_" + Support.token();
    store.db.update(
        "INSERT INTO api_keys(id,project_id,name,prefix,key_hash) VALUES(?,?,?,?,?)",
        id,
        project,
        name,
        key.substring(0, 16),
        Support.hash(key));
    store.audit(uid(req), (UUID) p.get("workspace_id"), "key.create", id.toString(), true, req);
    return Map.of("id", id, "key", key, "message", "SHOWN_ONCE");
  }

  @PostMapping("/api/projects/{project}/keys/{key}/revoke")
  @Transactional
  Map<String, String> revoke(
      @PathVariable UUID project,
      @PathVariable UUID key,
      @RequestBody JsonNode b,
      HttpServletRequest req) {
    var p = store.project(uid(req), project);
    verify(b, "key.revoke", project + ":" + key, req);
    int changed =
        store.db.update(
            "UPDATE api_keys SET revoked_at=now() WHERE id=? AND project_id=? AND revoked_at IS"
                + " NULL",
            key,
            project);
    if (changed == 0) throw new ApiError(404, "KEY_NOT_FOUND");
    store.audit(uid(req), (UUID) p.get("workspace_id"), "key.revoke", key.toString(), true, req);
    return Map.of("status", "revoked");
  }

  @PostMapping("/api/projects/{project}/keys/{key}/delete")
  @Transactional
  Map<String, String> deleteKey(
      @PathVariable UUID project, @PathVariable UUID key, HttpServletRequest req) {
    var p = store.project(uid(req), project);
    var keys =
        store.db.queryForList(
            "SELECT revoked_at FROM api_keys WHERE id=? AND project_id=? AND deleted_at IS NULL FOR"
                + " UPDATE",
            key,
            project);
    if (keys.isEmpty()) throw new ApiError(404, "KEY_NOT_FOUND");
    if (keys.get(0).get("revoked_at") == null) throw new ApiError(409, "KEY_MUST_BE_REVOKED");
    store.db.update("UPDATE api_keys SET deleted_at=now() WHERE id=?", key);
    store.audit(uid(req), (UUID) p.get("workspace_id"), "key.delete", key.toString(), true, req);
    return Map.of("status", "deleted");
  }

  @PostMapping("/api/projects/{project}/alerts/{id}/delete")
  @Transactional
  Map<String, String> deleteAlert(
      @PathVariable UUID project, @PathVariable UUID id, HttpServletRequest req) {
    var p = store.project(uid(req), project);
    if (store.db.update(
            "UPDATE alert_rules SET enabled=false,deleted_at=now() WHERE id=? AND project_id=? AND"
                + " deleted_at IS NULL",
            id,
            project)
        == 0) throw new ApiError(404, "ALERT_NOT_FOUND");
    store.audit(uid(req), (UUID) p.get("workspace_id"), "alert.delete", id.toString(), true, req);
    return Map.of("status", "deleted");
  }

  @PostMapping("/api/projects/{project}/delete")
  @Transactional
  Map<String, String> delete(
      @PathVariable UUID project, @RequestBody JsonNode b, HttpServletRequest req) {
    var p = store.project(uid(req), project);
    Support.require(b.path("confirm").asText().equals(p.get("name")), "CONFIRMATION_REQUIRED");
    verify(b, "project.delete", project.toString(), req);
    store.db.update("UPDATE projects SET deleted_at=now() WHERE id=?", project);
    store.db.update("UPDATE api_keys SET revoked_at=now() WHERE project_id=?", project);
    store.audit(
        uid(req), (UUID) p.get("workspace_id"), "project.delete", project.toString(), true, req);
    return Map.of("status", "deletion_pending");
  }

  String retained() {
    return " AND o.started_at>now()-make_interval(days=>CASE w.plan WHEN 'Pro' THEN 1096 WHEN"
        + " 'Core' THEN 90 ELSE 30 END) ";
  }

  String source() {
    return " FROM objects o JOIN projects p ON p.id=o.project_id JOIN workspaces w ON"
        + " w.id=p.workspace_id WHERE o.project_id=? AND w.owner_id=? AND p.deleted_at IS"
        + " NULL"
        + retained();
  }

  List<Map<String, Object>> decode(List<Map<String, Object>> rows) {
    for (var r : rows)
      if (r.get("data") != null) r.put("data", Support.parse(r.get("data").toString()));
    return rows;
  }

  @GetMapping("/api/projects/{project}/overview")
  Map<String, Object> overview(@PathVariable UUID project, HttpServletRequest req) {
    store.project(uid(req), project);
    String q = source();
    var summary =
        store.db.queryForMap(
            "SELECT count(*) AS objects,count(*) FILTER(WHERE o.type='trace') AS traces,count(*)"
                + " FILTER(WHERE o.data->>'observationType'='generation') AS calls,count(*)"
                + " FILTER(WHERE o.data->>'status'='error') AS errors,sum(o.cost) AS cost,count(*)"
                + " FILTER(WHERE o.data->>'observationType'='generation' AND o.cost IS NULL) AS"
                + " unknown_costs,sum((o.data->'usage'->>'inputTokens')::numeric+(o.data->'usage'->>'outputTokens')::numeric)"
                + " AS tokens,percentile_cont(0.95) WITHIN GROUP(ORDER BY"
                + " (o.data->>'durationMs')::numeric) AS p95"
                + q,
            project,
            uid(req));
    var daily =
        store.db.queryForList(
            "SELECT (o.started_at AT TIME ZONE 'UTC')::date AS day,count(*) AS calls,sum(o.cost) AS"
                + " cost"
                + q
                + " GROUP BY 1 ORDER BY 1 DESC LIMIT 30",
            project,
            uid(req));
    var models =
        store.db.queryForList(
            "SELECT o.data->>'model' AS model,count(*) AS calls,sum(o.cost) AS cost"
                + q
                + " AND o.data->>'model' IS NOT NULL GROUP BY 1 ORDER BY calls DESC LIMIT 20",
            project,
            uid(req));
    return Map.of("summary", summary, "daily", daily, "models", models);
  }

  @GetMapping("/api/projects/{project}/traces")
  Map<String, Object> traces(
      @PathVariable UUID project,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "0") int page,
      HttpServletRequest req) {
    store.project(uid(req), project);
    Support.require(page >= 0 && page <= 100000 && search.length() <= 128, "INVALID_FILTER");
    String where =
        source()
            + " AND o.type='trace' AND (?='' OR o.data->>'status'=?) AND (?='' OR o.object_id ILIKE"
            + " ? OR o.data->>'name' ILIKE ?)";
    Object[] args = {
      project, uid(req), status, status, search, "%" + search + "%", "%" + search + "%"
    };
    var params = new ArrayList<>(Arrays.asList(args));
    params.add(page * 50);
    var rows =
        decode(
            store.db.queryForList(
                "SELECT o.*" + where + " ORDER BY o.started_at DESC LIMIT 50 OFFSET ?",
                params.toArray()));
    long count = store.db.queryForObject("SELECT count(*)" + where, Long.class, args);
    return Map.of("items", rows, "total", count, "page", page);
  }

  @GetMapping("/api/projects/{project}/traces/{trace}")
  Map<String, Object> trace(
      @PathVariable UUID project, @PathVariable String trace, HttpServletRequest req) {
    store.project(uid(req), project);
    Support.require(trace.matches("[0-9a-fA-F]{32}"), "INVALID_TRACE_ID");
    return Map.of(
        "objects",
        decode(
            store.db.queryForList(
                "SELECT o.*,CASE WHEN o.type='observation' THEN NOT EXISTS(SELECT 1 FROM objects t"
                    + " WHERE t.project_id=o.project_id AND t.type='trace' AND"
                    + " t.trace_id=o.trace_id) WHEN o.type='score' THEN NOT EXISTS(SELECT 1 FROM"
                    + " objects t WHERE t.project_id=o.project_id AND t.type=o.data->>'targetType'"
                    + " AND t.object_id=o.data->>'targetId') ELSE false END AS unmatched"
                    + source()
                    + " AND (o.trace_id=? OR (o.type='score' AND EXISTS (SELECT 1 FROM objects t"
                    + " WHERE t.project_id=o.project_id AND t.trace_id=? AND"
                    + " t.type=o.data->>'targetType' AND t.object_id=o.data->>'targetId'))) ORDER"
                    + " BY o.started_at",
                project,
                uid(req),
                trace,
                trace)));
  }

  @GetMapping("/api/projects/{project}/objects")
  List<Map<String, Object>> objects(@PathVariable UUID project, HttpServletRequest req) {
    store.project(uid(req), project);
    return decode(
        store.db.queryForList(
            "SELECT o.*,CASE WHEN o.type='observation' THEN NOT EXISTS(SELECT 1 FROM objects t"
                + " WHERE t.project_id=o.project_id AND t.type='trace' AND t.trace_id=o.trace_id)"
                + " WHEN o.type='score' THEN NOT EXISTS(SELECT 1 FROM objects t WHERE"
                + " t.project_id=o.project_id AND t.type=o.data->>'targetType' AND"
                + " t.object_id=o.data->>'targetId') ELSE false END AS unmatched"
                + source()
                + " ORDER BY o.started_at DESC LIMIT 100",
            project,
            uid(req)));
  }

  @GetMapping("/api/projects/{project}/activity")
  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  Map<String, Object> activity(
      @PathVariable UUID project,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "5") int size,
      HttpServletRequest req) {
    store.project(uid(req), project);
    Support.require(page >= 0 && size >= 1 && size <= 100, "INVALID_PAGE");
    long total =
        store.db.queryForObject("SELECT count(*)" + source(), Long.class, project, uid(req));
    var rows =
        decode(
            store.db.queryForList(
                "SELECT o.*"
                    + source()
                    + " ORDER BY o.started_at DESC,o.type,o.object_id LIMIT ? OFFSET ?",
                project,
                uid(req),
                size,
                (long) page * size));
    return Map.of("items", rows, "total", total, "page", page, "size", size);
  }

  @GetMapping("/api/projects/{project}/activity/export")
  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  void exportActivity(
      @PathVariable UUID project,
      @RequestParam String format,
      @RequestParam(defaultValue = "en") String lang,
      HttpServletRequest req,
      jakarta.servlet.http.HttpServletResponse res)
      throws java.io.IOException {
    store.project(uid(req), project);
    Support.require(Set.of("xlsx", "pdf").contains(format), "INVALID_FORMAT");
    res.setContentType(
        format.equals("xlsx")
            ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            : "application/pdf");
    res.setHeader(
        "Content-Disposition",
        "attachment; filename=\"metertrail-activity-" + project + "." + format + "\"");
    res.setHeader("Cache-Control", "no-store");
    boolean zh = lang.equals("zh-CN");
    var excel = format.equals("xlsx") ? new ActivityExport.Excel(res.getOutputStream(), zh) : null;
    var pdf = format.equals("pdf") ? new ActivityExport.Pdf(zh) : null;
    try {
      store.db.query(
          con -> {
            var stmt =
                con.prepareStatement(
                    "SELECT o.started_at,o.object_id,o.type,o.data,o.cost"
                        + source()
                        + " ORDER BY o.started_at DESC,o.type,o.object_id");
            stmt.setObject(1, project);
            stmt.setObject(2, uid(req));
            stmt.setFetchSize(500);
            return stmt;
          },
          (org.springframework.jdbc.core.RowCallbackHandler)
              rs -> {
                Map<String, Object> row = new HashMap<>();
                for (String key : List.of("started_at", "object_id", "type", "data", "cost"))
                  row.put(key, rs.getObject(key));
                try {
                  var values = ActivityExport.row(row, zh);
                  if (excel != null) excel.add(values);
                  else pdf.add(values);
                } catch (java.io.IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
      if (pdf != null) pdf.save(res.getOutputStream());
    } finally {
      if (excel != null) excel.close();
      if (pdf != null) pdf.close();
    }
  }

  @GetMapping("/api/projects/{project}/analytics")
  List<Map<String, Object>> analytics(
      @PathVariable UUID project,
      @RequestParam(defaultValue = "customerId") String dimension,
      HttpServletRequest req) {
    store.project(uid(req), project);
    Support.require(
        Set.of("userId", "customerId", "feature").contains(dimension), "INVALID_DIMENSION");
    return store.db.queryForList(
        "SELECT o.data->>? AS dimension,count(*) AS objects,sum(o.cost) AS cost,count(*)"
            + " FILTER(WHERE o.data->>'observationType'='generation' AND o.cost IS NULL) AS"
            + " unknown_costs,sum((o.data->'usage'->>'inputTokens')::numeric+(o.data->'usage'->>'outputTokens')::numeric)"
            + " AS tokens"
            + source()
            + " GROUP BY 1 ORDER BY objects DESC LIMIT 100",
        dimension,
        project,
        uid(req));
  }

  @GetMapping("/api/projects/{project}/prices")
  List<Map<String, Object>> prices(@PathVariable UUID project, HttpServletRequest req) {
    store.project(uid(req), project);
    return store.db.queryForList(
        "SELECT * FROM price_versions WHERE project_id=? ORDER BY effective_at DESC", project);
  }

  @PostMapping("/api/projects/{project}/prices")
  @Transactional
  Map<String, Object> price(
      @PathVariable UUID project, @RequestBody JsonNode b, HttpServletRequest req) {
    var p = store.project(uid(req), project);
    String provider = Support.text(b, "provider", 64), model = Support.text(b, "model", 256);
    BigDecimal input = decimal(b, "inputRate"),
        output = decimal(b, "outputRate"),
        cached = decimal(b, "cachedRate");
    Instant effective;
    try {
      effective = Instant.parse(b.path("effectiveAt").asText());
    } catch (Exception e) {
      throw new ApiError(400, "INVALID_TIMESTAMP");
    }
    UUID id = UUID.randomUUID();
    store.db.update(
        "INSERT INTO"
            + " price_versions(id,project_id,provider,model,effective_at,input_rate,output_rate,cached_rate)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        project,
        provider,
        model,
        Timestamp.from(effective),
        input,
        output,
        cached);
    store.audit(uid(req), (UUID) p.get("workspace_id"), "price.create", id.toString(), true, req);
    return Map.of("id", id);
  }

  static BigDecimal decimal(JsonNode b, String k) {
    try {
      BigDecimal n = new BigDecimal(b.path(k).asText());
      Support.require(
          n.signum() >= 0 && n.compareTo(new BigDecimal("1000000")) <= 0 && n.scale() <= 12,
          "INVALID_AMOUNT");
      return n;
    } catch (NumberFormatException e) {
      throw new ApiError(400, "INVALID_AMOUNT");
    }
  }

  @GetMapping("/api/billing")
  Map<String, Object> billing(HttpServletRequest req) {
    var w = store.workspace(uid(req));
    if (w.get("scheduled_change") != null) w.put("scheduled_change", Support.parse(w.get("scheduled_change").toString()));
    return Map.of(
        "workspace",
        w,
        "overage",
        Billing.overage(((Number) w.get("used_units")).longValue()),
        "included",
        Billing.included((String) w.get("plan")),
        "payments",
        store.db.queryForList(
            "SELECT * FROM payments WHERE workspace_id=? ORDER BY created_at DESC LIMIT 100",
            w.get("id")),
        "adjustments",
        store.db.queryForList("SELECT id,transaction_id,action,status,amount,currency,updated_at FROM payment_adjustments WHERE workspace_id=? ORDER BY updated_at DESC LIMIT 100", w.get("id")),
        "ledger",
        store.db.queryForList(
            "SELECT project_id,sum(units) AS units FROM usage_ledger WHERE workspace_id=? AND"
                + " period_start=? GROUP BY project_id",
            w.get("id"),
            w.get("period_start")));
  }

  @PostMapping("/api/billing/overage")
  @Transactional
  Map<String, String> overage(@RequestBody JsonNode b, HttpServletRequest req) {
    var base = store.workspace(uid(req));
    var w = store.db.queryForMap("SELECT * FROM workspaces WHERE id=? FOR UPDATE", base.get("id"));
    boolean enabled = b.path("enabled").asBoolean();
    BigDecimal cap = decimal(b, "cap");
    Support.require(
        !enabled || (!w.get("plan").equals("Free") && b.path("consent").asBoolean()),
        "BILLING_CONSENT_REQUIRED");
    String target = enabled + ":" + cap.stripTrailingZeros().toPlainString();
    verify(b, "billing.overage", target, req);
    Support.require(
        cap.compareTo(Billing.overage(((Number) w.get("used_units")).longValue())) >= 0,
        "CAP_BELOW_ACCRUED");
    store.db.update(
        "UPDATE workspaces SET auto_overage=?,overage_cap=? WHERE id=?", enabled, cap, w.get("id"));
    store.audit(uid(req), (UUID) w.get("id"), "billing.overage", target, true, req);
    return Map.of("status", "updated");
  }

  @GetMapping("/api/projects/{project}/alerts")
  Map<String, Object> alerts(@PathVariable UUID project, HttpServletRequest req) {
    store.project(uid(req), project);
    return Map.of(
        "rules",
        store.db.queryForList(
            "SELECT * FROM alert_rules WHERE project_id=? AND deleted_at IS NULL ORDER BY name",
            project),
        "events",
        store.db.queryForList(
            "SELECT e.*,r.name FROM alert_events e JOIN alert_rules r ON r.id=e.rule_id WHERE"
                + " r.project_id=? ORDER BY e.created_at DESC LIMIT 100",
            project));
  }

  @PostMapping("/api/projects/{project}/alerts")
  @Transactional
  Map<String, Object> alert(
      @PathVariable UUID project, @RequestBody JsonNode b, HttpServletRequest req) {
    var p = store.project(uid(req), project);
    String metric = b.path("metric").asText();
    Support.require(
        Set.of("error_count", "cost_usd", "latency_ms").contains(metric), "INVALID_METRIC");
    BigDecimal threshold = decimal(b, "threshold");
    Support.require(threshold.signum() > 0, "INVALID_THRESHOLD");
    UUID id = UUID.randomUUID();
    store.db.update(
        "INSERT INTO alert_rules(id,project_id,name,metric,threshold) VALUES(?,?,?,?,?)",
        id,
        project,
        Support.text(b, "name", 128),
        metric,
        threshold);
    store.audit(uid(req), (UUID) p.get("workspace_id"), "alert.create", id.toString(), true, req);
    return Map.of("id", id);
  }

  @PostMapping("/api/projects/{project}/alerts/{id}")
  @Transactional
  Map<String, String> toggle(
      @PathVariable UUID project,
      @PathVariable UUID id,
      @RequestBody JsonNode b,
      HttpServletRequest req) {
    var p = store.project(uid(req), project);
    if (store.db.update(
            "UPDATE alert_rules SET enabled=? WHERE id=? AND project_id=? AND deleted_at IS NULL",
            b.path("enabled").asBoolean(),
            id,
            project)
        == 0) throw new ApiError(404, "ALERT_NOT_FOUND");
    store.audit(uid(req), (UUID) p.get("workspace_id"), "alert.toggle", id.toString(), true, req);
    return Map.of("status", "updated");
  }

  @GetMapping("/api/audit")
  List<Map<String, Object>> audit(
      @RequestParam(defaultValue = "0") int page, HttpServletRequest req) {
    Support.require(page >= 0 && page <= 100000, "INVALID_PAGE");
    return store.db.queryForList(
        "SELECT a.* FROM audit_logs a WHERE a.user_id=? OR a.workspace_id IN(SELECT id FROM"
            + " workspaces WHERE owner_id=?) ORDER BY a.id DESC LIMIT 100 OFFSET ?",
        uid(req),
        uid(req),
        page * 100);
  }
}
