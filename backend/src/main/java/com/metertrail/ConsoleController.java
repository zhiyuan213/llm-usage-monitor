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
        store
            .queries()
            .queryForList(
                "SELECT id,status,received,accepted,duplicates,rejected,errors,attempts,created_at"
                    + " FROM ingest_batches WHERE project_id=? ORDER BY created_at DESC LIMIT 50",
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

  @org.springframework.beans.factory.annotation.Value(
      "${metertrail.usage-collection-enabled:false}")
  boolean collectionEnabled;

  final Store store;
  final Challenges challenges;
  final RateLimits limits;
  final TraceCountCache traceCounts;
  final org.springframework.transaction.support.TransactionTemplate countTransactions;

  ConsoleController(
      Store store,
      Challenges challenges,
      RateLimits limits,
      TraceCountCache traceCounts,
      @org.springframework.beans.factory.annotation.Qualifier("readTransactionManager")
          org.springframework.transaction.PlatformTransactionManager readTransactions) {
    this.store = store;
    this.challenges = challenges;
    this.limits = limits;
    this.traceCounts = traceCounts;
    this.countTransactions =
        new org.springframework.transaction.support.TransactionTemplate(readTransactions);
    this.countTransactions.setReadOnly(true);
    this.countTransactions.setTimeout(3);
  }

  UUID uid(HttpServletRequest req) {
    return SessionFilter.uid(req);
  }

  @GetMapping("/api/projects")
  List<Map<String, Object>> projects(HttpServletRequest req) {
    return store
        .queries()
        .queryForList(
            "SELECT p.*,(SELECT count(*) FROM objects o WHERE o.project_id=p.id) AS object_count"
                + " FROM projects p JOIN workspaces w ON w.id=p.workspace_id WHERE w.owner_id=? AND"
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
    return store
        .queries()
        .queryForList(
            "SELECT id,name,prefix,created_at,revoked_at FROM api_keys WHERE project_id=? AND"
                + " deleted_at IS NULL ORDER BY created_at DESC",
            project);
  }

  @GetMapping("/api/projects/{project}/keys/creation-policy")
  Map<String, Boolean> keyCreationPolicy(@PathVariable UUID project, HttpServletRequest req) {
    store.project(uid(req), project);
    return Map.of("verificationRequired", hasCreatedKey(project));
  }

  boolean hasCreatedKey(UUID project) {
    // Include revoked and soft-deleted keys: deleting a key must never reset eligibility.
    return Boolean.TRUE.equals(store.db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM api_keys WHERE project_id=?)", Boolean.class, project));
  }

  @PostMapping("/api/projects/{project}/keys")
  @Transactional
  Map<String, Object> key(
      @PathVariable UUID project, @RequestBody JsonNode b, HttpServletRequest req) {
    var p = store.project(uid(req), project);
    String name = Support.text(b, "name", 128);
    // Serialize first-key decisions for this project, including concurrent requests.
    store.db.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, project);
    if (hasCreatedKey(project)) {
      if (b.path("challengeId").asText().isBlank()) throw new ApiError(400, "INVALID_VERIFICATION");
      verify(b, "key.create", project + ":" + name, req);
    }
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
        store
            .queries()
            .queryForList(
                "SELECT revoked_at FROM api_keys WHERE id=? AND project_id=? AND deleted_at IS NULL"
                    + " FOR UPDATE",
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
    Support.require(b.path("confirm").asText().equals("delete"), "CONFIRMATION_REQUIRED");
    verify(b, "project.delete", project.toString(), req);
    store.db.update("UPDATE projects SET deleted_at=now() WHERE id=?", project);
    store.db.update("UPDATE api_keys SET revoked_at=now() WHERE project_id=?", project);
    store.audit(
        uid(req), (UUID) p.get("workspace_id"), "project.delete", project.toString(), true, req);
    return Map.of("status", "deletion_pending");
  }

  String retained() {
    return " AND o.started_at>workspace_retention_cutoff(w) ";
  }

  String source() {
    return " FROM objects o JOIN projects p ON p.id=o.project_id JOIN workspaces w ON"
        + " w.id=p.workspace_id WHERE o.project_id=? AND w.owner_id=? AND p.deleted_at IS"
        + " NULL"
        + retained();
  }

  List<Map<String, Object>> decode(List<Map<String, Object>> rows) {
    for (var r : rows) {
      if (r.get("data") != null) r.put("data", Support.parse(r.get("data").toString()));
      if (r.get("applied_rates") != null) r.put("applied_rates", Support.parse(r.get("applied_rates").toString()));
    }
    return rows;
  }

  @GetMapping("/api/projects/{project}/overview")
  Map<String, Object> overview(@PathVariable UUID project, HttpServletRequest req) {
    store.project(uid(req), project);
    var overviewArgs=new ArrayList<Object>(List.of(project,uid(req)));
    String q = source()+ReportWindow.filter(req,overviewArgs);
    var summary =
        store
            .queries()
            .queryForMap(
                "SELECT count(*) AS objects,count(*) FILTER(WHERE o.type='trace') AS"
                    + " traces,count(*) FILTER(WHERE o.type='observation' AND (o.data->>'durationMs')::numeric>=p.slow_call_threshold_ms) AS slow_requests,"
                    + "count(*) FILTER(WHERE o.type='observation' AND o.data->>'status'='error') AS failed_requests,count(*) FILTER(WHERE o.type='observation' AND o.data->>'observationType'='generation') AS"
                    + " calls,count(*) FILTER(WHERE o.data->>'status'='error') AS"
                    + " errors,sum(o.cost) FILTER(WHERE o.type='observation' AND o.data->>'observationType'='generation') AS cost,count(*) FILTER(WHERE"
                    + " o.type='observation' AND o.data->>'observationType'='generation' AND o.cost IS NULL) AS"
                    + " unknown_costs,sum((o.data->'usage'->>'inputTokens')::numeric+(o.data->'usage'->>'outputTokens')::numeric)"
                    + " FILTER(WHERE o.type='observation' AND o.data->>'observationType'='generation') AS tokens,percentile_cont(0.95) WITHIN GROUP(ORDER BY"
                    + " (o.data->>'durationMs')::numeric) FILTER(WHERE o.type='trace') AS p95"
                    + q,
                overviewArgs.toArray());
    var daily =
        store
            .queries()
            .queryForList(
                "SELECT (o.started_at AT TIME ZONE 'UTC')::date AS day,count(*) FILTER(WHERE o.type='trace') AS"
                    + " calls,sum(o.cost) AS cost"
                    + q
                    + " GROUP BY 1 ORDER BY 1 DESC LIMIT 366",
                overviewArgs.toArray());
    var models =
        store
            .queries()
            .queryForList(
                "SELECT o.data->>'model' AS model,count(*) AS calls,sum(o.cost) AS cost"
                    + q
                    + " AND o.data->>'model' IS NOT NULL GROUP BY 1 ORDER BY calls DESC LIMIT 20",
                overviewArgs.toArray());
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
    StringBuilder where = new StringBuilder(source()).append(" AND o.type='trace'");
    var args = new ArrayList<Object>(List.of(project, uid(req)));
    if (!status.isEmpty()) {
      where.append(" AND o.data->>'status'=?");
      args.add(status);
    }
    if (!search.isEmpty()) {
      where.append(" AND (o.object_id ILIKE ? OR o.data->>'name' ILIKE ?)");
      args.add("%" + search + "%");
      args.add("%" + search + "%");
    }
    var params = new ArrayList<>(args);
    params.add(page * 50);
    var rows =
        decode(
            store
                .queries()
                .queryForList(
                    "SELECT o.*" + where + " ORDER BY o.started_at DESC LIMIT 50 OFFSET ?",
                    params.toArray()));
    long count;
    if (search.isEmpty() && status.isEmpty()) {
      count = countTraces(project, uid(req));
    } else {
      count = store.queries().queryForObject("SELECT count(*)" + where, Long.class, args.toArray());
    }
    return Map.of("items", rows, "total", count, "page", page);
  }

  long countTraces(UUID project, UUID owner) {
    return traceCounts.get(
        project,
        owner,
        () ->
            countTransactions.execute(
                status -> {
                  // One exact count must not consume parallel DB workers needed by ingestion.
                  store.read.execute("SET LOCAL max_parallel_workers_per_gather=0");
                  return store.read.query(
                      con -> {
                        var stmt =
                            con.prepareStatement(
                                "SELECT count(*) FROM objects o WHERE o.project_id=? AND"
                                    + " o.type='trace' AND o.started_at>(SELECT"
                                    + " workspace_retention_cutoff(w) FROM projects p JOIN"
                                    + " workspaces w ON w.id=p.workspace_id WHERE p.id=? AND"
                                    + " w.owner_id=? AND p.deleted_at IS NULL)");
                        stmt.setObject(1, project);
                        stmt.setObject(2, project);
                        stmt.setObject(3, owner);
                        stmt.setQueryTimeout(3);
                        return stmt;
                      },
                      rs -> {
                        rs.next();
                        return rs.getLong(1);
                      });
                }));
  }

  @GetMapping("/api/projects/{project}/traces/{trace}")
  Map<String, Object> trace(
      @PathVariable UUID project, @PathVariable String trace, HttpServletRequest req) {
    store.project(uid(req), project);
    Support.require(trace.matches("[0-9a-fA-F]{32}"), "INVALID_TRACE_ID");
    return Map.of(
        "objects",
        decode(
            store
                .queries()
                .queryForList(
                    "SELECT o.*,"+CostEvidence.reason("o")+" AS cost_reason,pv.effective_at AS price_effective_at,coalesce((o.applied_rates->>'input')::numeric,pv.input_rate) AS input_rate,coalesce((o.applied_rates->>'output')::numeric,pv.output_rate) AS output_rate,coalesce((o.applied_rates->>'cached')::numeric,pv.cached_rate) AS cached_rate,pv.source AS price_source,CASE WHEN o.type='observation' THEN NOT EXISTS(SELECT 1 FROM"
                        + " objects t WHERE t.project_id=o.project_id AND t.type='trace' AND"
                        + " t.trace_id=o.trace_id) WHEN o.type='score' THEN NOT EXISTS(SELECT 1"
                        + " FROM objects t WHERE t.project_id=o.project_id AND"
                        + " t.type=o.data->>'targetType' AND t.object_id=o.data->>'targetId') ELSE"
                        + " false END AS unmatched"
                        + source().replace(" WHERE", " LEFT JOIN price_versions pv ON pv.id=o.price_version WHERE")
                        + " AND (o.trace_id=? OR (o.type='score' AND EXISTS (SELECT 1 FROM objects"
                        + " t WHERE t.project_id=o.project_id AND t.trace_id=? AND"
                        + " t.type=o.data->>'targetType' AND t.object_id=o.data->>'targetId')))"
                        + " ORDER BY o.started_at",
                    project,
                    uid(req),
                    trace,
                    trace)));
  }

  @PostMapping("/api/projects/{project}/traces/{trace}/costs")
  @Transactional
  Map<String,Object> correctCosts(@PathVariable UUID project,@PathVariable String trace,
      @RequestBody JsonNode body,HttpServletRequest req) {
    UUID user=uid(req);var p=store.project(user,project);
    Support.require(trace.matches("[0-9a-fA-F]{32}"),"INVALID_TRACE_ID");
    JsonNode changes=body.path("changes");
    Support.require(changes.isArray()&&changes.size()>0&&changes.size()<=1000,"INVALID_COST_CORRECTION");
    var ordered=new TreeMap<String,JsonNode>();
    for(JsonNode change:changes) {
      String id=change.path("objectId").asText("");
      Support.require(!id.isBlank()&&id.length()<=128&&ordered.putIfAbsent(id,change)==null,"INVALID_COST_CORRECTION");
      Support.require(change.path("revision").isIntegralNumber()&&change.path("revision").canConvertToLong()&&change.path("revision").asLong()>0&&change.has("expectedCost")&&(change.get("expectedCost").isNull()||change.get("expectedCost").isNumber()),"INVALID_COST_CORRECTION");
      Support.require(change.path("cost").isNumber(),"INVALID_COST_CORRECTION");
      BigDecimal amount=change.get("cost").decimalValue();
      Support.require(amount.signum()>=0&&amount.scale()<=12&&amount.precision()-amount.scale()<=24,"INVALID_COST_CORRECTION");
    }
    int updated=0;
    for(var entry:ordered.entrySet()) {
      JsonNode change=entry.getValue();
      var rows=store.db.queryForList("SELECT o.* FROM objects o JOIN projects p ON p.id=o.project_id JOIN workspaces w ON w.id=p.workspace_id WHERE o.project_id=? AND o.trace_id=? AND o.type='observation' AND o.object_id=? AND o.started_at>workspace_retention_cutoff(w) FOR UPDATE OF o",project,trace,entry.getKey());
      if(rows.isEmpty())throw new ApiError(404,"OBJECT_NOT_FOUND");
      var row=rows.get(0);BigDecimal before=(BigDecimal)row.get("cost");
      BigDecimal expected=change.get("expectedCost").isNull()?null:change.get("expectedCost").decimalValue();
      if(((Number)row.get("revision")).longValue()!=change.get("revision").asLong()||(before==null?expected!=null:expected==null||before.compareTo(expected)!=0))throw new ApiError(409,"COST_CHANGED");
      BigDecimal amount=change.get("cost").decimalValue();
      if(before!=null&&before.compareTo(amount)==0)continue;
      var evidence=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();evidence.put("manual",true);evidence.put("source","console");evidence.put("correctedAt",Instant.now().toString());evidence.put("correctedBy",user.toString());
      store.db.update("UPDATE objects SET cost=?,applied_rates=?::jsonb,price_version=NULL WHERE project_id=? AND type='observation' AND object_id=?",amount,evidence.toString(),project,entry.getKey());
      var audit=evidence.deepCopy();audit.put("objectId",entry.getKey());audit.put("revision",change.get("revision").asLong());audit.put("beforeCost",before);audit.put("afterCost",amount);audit.put("beforeRates",Objects.toString(row.get("applied_rates"),"null"));
      store.audit(user,(UUID)p.get("workspace_id"),"observation.cost.correct",audit.toString(),true,req);updated++;
    }
    return Map.of("updated",updated);
  }

  @GetMapping("/api/projects/{project}/objects")
  List<Map<String, Object>> objects(@PathVariable UUID project, HttpServletRequest req) {
    store.project(uid(req), project);
    return decode(
        store
            .queries()
            .queryForList(
                "SELECT o.*,CASE WHEN o.type='observation' THEN NOT EXISTS(SELECT 1 FROM objects t"
                    + " WHERE t.project_id=o.project_id AND t.type='trace' AND"
                    + " t.trace_id=o.trace_id) WHEN o.type='score' THEN NOT EXISTS(SELECT 1 FROM"
                    + " objects t WHERE t.project_id=o.project_id AND t.type=o.data->>'targetType'"
                    + " AND t.object_id=o.data->>'targetId') ELSE false END AS unmatched"
                    + source()
                    + " ORDER BY o.started_at DESC LIMIT 100",
                project,
                uid(req)));
  }

  @GetMapping("/api/projects/{project}/activity")
  @Transactional(
      transactionManager = "readTransactionManager",
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  Map<String, Object> activity(
      @PathVariable UUID project,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "5") int size,
      HttpServletRequest req) {
    store.project(uid(req), project);
    Support.require(page >= 0 && size >= 1 && size <= 100, "INVALID_PAGE");
    var args=new ArrayList<Object>(List.of(project,uid(req)));
    String activitySource=source()+ReportWindow.filter(req,args);
    long total =
        store.queries().queryForObject("SELECT count(*)" + activitySource, Long.class, args.toArray());
    var rowArgs=new ArrayList<Object>(args);rowArgs.add(size);rowArgs.add((long)page*size);
    var rows =
        decode(
            store
                .queries()
                .queryForList(
                    "SELECT o.*"
                        + activitySource
                        + " ORDER BY o.started_at DESC,o.type,o.object_id LIMIT ? OFFSET ?",
                    rowArgs.toArray()));
    return Map.of("items", rows, "total", total, "page", page, "size", size);
  }

  @GetMapping("/api/projects/{project}/activity/export")
  @Transactional(
      transactionManager = "readTransactionManager",
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
    var exportArgs=new ArrayList<Object>(List.of(project,uid(req)));
    String exportSource=source()+ReportWindow.filter(req,exportArgs);
    boolean zh = lang.equals("zh-CN");
    var excel = format.equals("xlsx") ? new ActivityExport.Excel(res.getOutputStream(), zh) : null;
    var pdf = format.equals("pdf") ? new ActivityExport.Pdf(zh) : null;
    try {
      store
          .queries()
          .query(
              con -> {
                var stmt =
                    con.prepareStatement(
                        "SELECT o.started_at,o.object_id,o.type,o.data,o.cost"
                            + exportSource
                            + " ORDER BY o.started_at DESC,o.type,o.object_id");
                for(int i=0;i<exportArgs.size();i++)stmt.setObject(i+1,exportArgs.get(i));
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
    return store
        .queries()
        .queryForList(
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
    var prices=store.queries().queryForList("SELECT * FROM price_versions WHERE project_id=? ORDER BY effective_at DESC",project);
    prices.forEach(p->{p.put("time_bands",Support.parse(p.get("time_bands").toString()));p.put("billing_rules",Support.parse(p.get("billing_rules").toString()));});
    return prices;
  }

  @GetMapping("/api/projects/{project}/pricing-status")
  Map<String,Object> pricingStatus(@PathVariable UUID project,HttpServletRequest req) {
    var p=store.project(uid(req),project);
    var result=new HashMap<String,Object>(store.queries().queryForMap("SELECT * FROM model_price_sync WHERE id=1"));
    result.put("public_pricing",p.get("public_pricing"));
    return result;
  }

  @GetMapping("/api/projects/{project}/public-prices")
  Map<String,Object> publicPrices(@PathVariable UUID project,
      @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String provider,
      @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size,
      HttpServletRequest req) {
    store.project(uid(req),project);
    Support.require(q.length()<=128 && provider.length()<=64 && page>=0 && page<=100000
        && size>=1 && size<=100,"INVALID_FILTER");
    String latest="WITH latest AS (SELECT DISTINCT ON(provider,model) * FROM price_versions"
        + " WHERE project_id IS NULL AND effective_at<=now() ORDER BY provider,model,effective_at DESC) ";
    String filter=" FROM latest p WHERE p.available AND (?='' OR p.provider=?)"
        + " AND (?='' OR position(lower(?) in lower(p.provider||' '||p.model))>0)";
    var items=store.queries().queryForList(latest+"SELECT p.id,p.provider,p.model,p.input_rate,p.output_rate,"
        + "p.cached_rate,p.cached_known,p.currency,p.effective_at,p.fetched_at,p.source,p.time_bands,p.billing_rules,"
        + "EXISTS(SELECT 1 FROM price_versions c WHERE c.project_id=? AND c.provider=p.provider"
        + " AND c.model=p.model AND c.effective_at<=now()) AS custom_override"
        + filter+" ORDER BY p.provider,p.model LIMIT ? OFFSET ?",
        project,provider,provider,q.trim(),q.trim(),size,page*size);
    items.forEach(p->{p.put("time_bands",Support.parse(p.get("time_bands").toString()));p.put("billing_rules",Support.parse(p.get("billing_rules").toString()));});
    long total=store.queries().queryForObject(latest+"SELECT count(*)"+filter,Long.class,
        provider,provider,q.trim(),q.trim());
    var providers=store.queries().queryForList(latest+"SELECT DISTINCT provider FROM latest WHERE available ORDER BY provider",String.class);
    return Map.of("items",items,"total",total,"providers",providers);
  }

  @PostMapping("/api/projects/{project}/pricing-policy")
  @Transactional
  Map<String,String> pricingPolicy(@PathVariable UUID project,@RequestBody JsonNode b,HttpServletRequest req) {
    var p=store.project(uid(req),project);
    Support.require(b.path("publicPricing").isBoolean(),"INVALID_PRICING_POLICY");
    store.db.update("UPDATE projects SET public_pricing=? WHERE id=?",b.path("publicPricing").asBoolean(),project);
    store.audit(uid(req),(UUID)p.get("workspace_id"),"project.pricing-policy",project.toString(),true,req);
    return Map.of("status","updated");
  }

  @PostMapping("/api/projects/{project}/prices")
  @Transactional
  Map<String, Object> price(
      @PathVariable UUID project, @RequestBody JsonNode b, HttpServletRequest req) {
    var p = store.project(uid(req), project);
    String provider = Support.text(b, "provider", 64), model = Support.text(b, "model", 256);
    Support.require(b.path("currency").asText("USD").equals("USD"),"INVALID_PRICE_CURRENCY");
    BigDecimal input = decimal(b, "inputRate"),
        output = decimal(b, "outputRate"),
        cached = decimal(b, "cachedRate");
    Instant effective;
    try {
      effective = Instant.parse(b.path("effectiveAt").asText());
    } catch (Exception e) {
      throw new ApiError(400, "INVALID_TIMESTAMP");
    }
    String rules;
    try { rules=PricingRules.validate(b.path("billingRules")).toString(); }
    catch(Exception ex) { throw new ApiError(400,"INVALID_PRICE_RULES"); }
    String bands;
    try { bands=ModelPricing.bands(b.path("timeBands")).toString(); }
    catch (Exception ex) { throw new ApiError(400,"INVALID_PRICE_TIME_BANDS"); }
    Support.require(rules.equals("[]")||bands.equals("[]"),"INVALID_PRICE_RULES");
    UUID id = UUID.randomUUID();
    store.db.update(
        "INSERT INTO"
            + " price_versions(id,project_id,provider,model,effective_at,input_rate,output_rate,cached_rate,time_bands,billing_rules)"
            + " VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)",
        id,
        project,
        provider,
        model,
        Timestamp.from(effective),
        input,
        output,
        cached, bands, rules);
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
    if (w.get("scheduled_change") != null)
      w.put("scheduled_change", Support.parse(w.get("scheduled_change").toString()));
    return Map.of(
        "workspace",
        w,
        "confirmedCheckouts",store.queries().queryForList("SELECT transaction_id FROM checkout_intents WHERE workspace_id=? AND payment_provider='creem' AND confirmed ORDER BY created_at DESC LIMIT 100",w.get("id")),
        "overage",
        ChargeLedger.accrued(w),
        "included",
        ChargeLedger.included(w),
        "payments",
        store
            .queries()
            .queryForList(
                "SELECT * FROM payments WHERE workspace_id=? ORDER BY created_at DESC LIMIT 100",
                w.get("id")),
        "adjustments",
        store
            .queries()
            .queryForList(
                "SELECT id,transaction_id,action,status,amount,currency,updated_at FROM"
                    + " payment_adjustments WHERE workspace_id=? ORDER BY updated_at DESC LIMIT"
                    + " 100",
                w.get("id")),
        "ledger",
        store
            .queries()
            .queryForList(
                "SELECT project_id,sum(units) AS units FROM usage_ledger WHERE workspace_id=? AND"
                    + " period_start=? GROUP BY project_id",
                w.get("id"),
                w.get("period_start")));
  }

  @GetMapping("/api/billing/history")
  @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  Map<String,Object> billingHistory(@RequestParam String kind,
      @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="5") int size,
      HttpServletRequest req) {
    Support.require(page>=0 && size>=1 && size<=100,"INVALID_PAGE");
    Support.require(Set.of("payments","adjustments").contains(kind),"INVALID_PAGE");
    var w=store.workspace(uid(req));
    String table=kind.equals("payments")?"payments":"payment_adjustments";
    String time=kind.equals("payments")?"created_at":"updated_at";
    long total=store.queries().queryForObject("SELECT count(*) FROM "+table+" WHERE workspace_id=?",Long.class,w.get("id"));
    return Map.of("total",total,"items",store.queries().queryForList("SELECT * FROM "+table+" WHERE workspace_id=? ORDER BY "+time+" DESC,id DESC LIMIT ? OFFSET ?",w.get("id"),size,(long)page*size));
  }

  @PostMapping("/api/billing/overage")
  @Transactional
  Map<String, String> overage(@RequestBody JsonNode b, HttpServletRequest req) {
    var base = store.workspace(uid(req));
    var w =
        store
            .queries()
            .queryForMap("SELECT * FROM workspaces WHERE id=? FOR UPDATE", base.get("id"));
    boolean enabled = b.path("enabled").asBoolean();
    if (enabled && (!collectionEnabled || "creem".equals(w.get("payment_provider")))) throw new ApiError(503, "USAGE_COLLECTION_NOT_CONFIGURED");
    BigDecimal cap = decimal(b, "cap");
    Support.require(
        !enabled || (!w.get("plan").equals("Free") && b.path("consent").asBoolean()),
        "BILLING_CONSENT_REQUIRED");
    String target = enabled + ":" + cap.stripTrailingZeros().toPlainString();
    verify(b, "billing.overage", target, req);
    Support.require(cap.compareTo(ChargeLedger.accrued(w)) >= 0, "CAP_BELOW_ACCRUED");
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
        store
            .queries()
            .queryForList(
                "SELECT * FROM alert_rules WHERE project_id=? AND deleted_at IS NULL ORDER BY name",
                project),
        "events",
        store
            .queries()
            .queryForList(
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
    return store
        .queries()
        .queryForList(
            "SELECT a.* FROM audit_logs a WHERE a.user_id=? OR a.workspace_id IN(SELECT id FROM"
                + " workspaces WHERE owner_id=?) ORDER BY a.id DESC LIMIT 100 OFFSET ?",
            uid(req),
            uid(req),
            page * 100);
  }
}
