package com.metertrail;

import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController
class QueryReportsController {
  final Store store;
  final ConsoleController console;

  QueryReportsController(Store store, ConsoleController console) {
    this.store = store;
    this.console = console;
  }

  record Report(
      String sql,
      String order,
      Object[] args,
      String[] headers,
      float[] widths,
      Function<Map<String, Object>, String[]> row) {}

  String val(Object value, String missing) {
    return value == null ? missing : value.toString();
  }

  String utc(Object value) {
    return java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withZone(java.time.ZoneOffset.UTC)
        .format(((java.sql.Timestamp) value).toInstant());
  }

  Report report(
      String kind,
      UUID project,
      String dimension,
      String search,
      String status,
      boolean zh,
      Integer year, Integer month,
      HttpServletRequest req) {
    UUID user = SessionFilter.uid(req);
    if (kind.equals("audit"))
      return new Report(
          "SELECT a.* FROM audit_logs a WHERE a.user_id=? OR a.workspace_id IN(SELECT id FROM"
              + " workspaces WHERE owner_id=?)",
          "id DESC",
          new Object[] {user, user},
          zh
              ? new String[] {"操作", "目标", "结果", "IP", "请求 ID", "时间 UTC"}
              : new String[] {"Action", "Target", "Result", "IP", "Request ID", "Time UTC"},
          new float[] {115, 200, 70, 100, 170, 115},
          r ->
              new String[] {
                val(r.get("action"), "—"),
                val(r.get("target"), "—"),
                Boolean.TRUE.equals(r.get("success"))
                    ? (zh ? "成功" : "Success")
                    : (zh ? "失败" : "Failed"),
                val(r.get("ip"), "—"),
                val(r.get("request_id"), "—"),
                utc(r.get("created_at"))
              });
    Support.require(Set.of("traces", "analytics").contains(kind), "INVALID_REPORT");
    Support.require(project != null, "PROJECT_REQUIRED");
    store.project(user, project);
    if (kind.equals("analytics")) {
      Support.require(
          Set.of("userId", "customerId", "feature").contains(dimension), "INVALID_DIMENSION");
      Support.require(year == null || (year>=2000 && year<=9998), "INVALID_FILTER");
      Support.require(month == null || (year != null && month>=1 && month<=12), "INVALID_FILTER");
      String dimensionLabel = dimension.equals("customerId") ? (zh ? "客户 ID" : "Customer ID")
          : dimension.equals("userId") ? (zh ? "用户 ID" : "User ID") : (zh ? "功能" : "Feature");
      var analyticsArgs = new ArrayList<Object>(List.of(dimension,project,user));
      String periodFilter=ReportWindow.filter(req,analyticsArgs);
      if(year!=null && periodFilter.isEmpty()) {
        var start=java.time.LocalDate.of(year,month==null?1:month,1);
        var end=month==null?start.plusYears(1):start.plusMonths(1);
        periodFilter=" AND o.started_at>=? AND o.started_at<?";
        analyticsArgs.add(java.sql.Timestamp.from(start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        analyticsArgs.add(java.sql.Timestamp.from(end.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
      }
      return new Report(
          "SELECT o.data->>? AS dimension,count(*) AS objects,sum(o.cost) AS cost,count(*)"
              + " FILTER(WHERE o.data->>'observationType'='generation' AND o.cost IS NULL) AS"
              + " unknown_costs,sum((o.data->'usage'->>'inputTokens')::numeric+(o.data->'usage'->>'outputTokens')::numeric)"
              + " AS tokens,max(o.started_at) AS last_activity_at"
              + console.source() + periodFilter
              + " GROUP BY 1",
          "objects DESC,dimension ASC NULLS LAST",
          analyticsArgs.toArray(),
          zh
              ? new String[] {dimensionLabel, "对象数", "已知 Token", "估算成本 USD", "未知成本调用", "最近活动时间 UTC"}
              : new String[] {
                dimensionLabel,
                "Objects",
                "Known tokens",
                "Estimated cost USD",
                "Unknown costs",
                "Last activity UTC"
              },
          new float[] {180, 80, 100, 130, 120, 160},
          r ->
              new String[] {
                val(r.get("dimension"), (zh ? "未设置" : "Not set: ") + dimensionLabel),
                val(r.get("objects"), "0"),
                val(r.get("tokens"), "—"),
                val(r.get("cost"), zh ? "未知" : "Unknown"),
                val(r.get("unknown_costs"), "0"),
                utc(r.get("last_activity_at"))
              });
    }
    Support.require(search.length() <= 128, "INVALID_FILTER");
    var traceArgs=new ArrayList<Object>(List.of(project,user,status,status,search,"%"+search+"%","%"+search+"%"));
    String traceWindow=ReportWindow.filter(req,traceArgs);
    String minimum=req.getParameter("minDurationMs");
    if(minimum!=null) {
      Support.require(minimum.matches("[0-9]{1,9}"),"INVALID_FILTER");
      traceWindow+=" AND (o.data->>'durationMs')::numeric>=?";
      traceArgs.add(Long.parseLong(minimum));
    }
    String observation=Optional.ofNullable(req.getParameter("observation")).orElse("");
    Support.require(Set.of("","slow","error").contains(observation),"INVALID_FILTER");
    if(!observation.isEmpty()) {
      var observationArgs=new ArrayList<Object>();
      String observationWindow=ReportWindow.filter(req,observationArgs).replace("o.started_at","g.started_at");
      traceWindow+=" AND EXISTS(SELECT 1 FROM objects g WHERE g.project_id=o.project_id AND g.trace_id=o.trace_id AND g.type='observation' AND g.started_at>workspace_retention_cutoff(w) AND "+(observation.equals("slow")?"(g.data->>'durationMs')::numeric>=p.slow_call_threshold_ms":"g.data->>'status'='error'")+observationWindow+")";
      traceArgs.addAll(observationArgs);
    }
    String cost=Optional.ofNullable(req.getParameter("cost")).orElse("all"),reason=Optional.ofNullable(req.getParameter("costReason")).orElse("");
    Support.require(Set.of("all","unknown","known").contains(cost),"INVALID_FILTER");
    Support.require(Set.of("","usage_missing","model_missing","pricing_evidence_missing","price_missing","price_unavailable","billing_rule_missing","billing_rule_ambiguous","cache_rate_missing","cache_write_rate_missing","currency_unsupported").contains(reason),"INVALID_FILTER");
    String costFilter=cost.equals("unknown")?" AND m.unknown_costs>0":cost.equals("known")?" AND m.priced_calls=m.generations AND m.generations>0":"";
    if(!reason.isEmpty()) {costFilter+=" AND EXISTS(SELECT 1 FROM objects g WHERE g.project_id=o.project_id AND g.trace_id=o.trace_id AND g.started_at>workspace_retention_cutoff(w) AND ("+CostEvidence.reason("g")+")=?)";traceArgs.add(reason);}

    String sort=Optional.ofNullable(req.getParameter("sort")).orElse("started_at");
    Support.require(Set.of("started_at","duration","tokens","estimated_cost").contains(sort),"INVALID_SORT");
    String direction=Optional.ofNullable(req.getParameter("direction")).orElse("desc");
    Support.require(Set.of("asc","desc").contains(direction),"INVALID_SORT");
    String order=(sort.equals("duration")?"(data->>'durationMs')::numeric":sort)+" "+direction+" NULLS LAST,object_id";
    return new Report(
        "SELECT o.*,m.models,m.tokens,m.estimated_cost,m.generations,m.priced_calls,m.unknown_costs,coalesce(s.data->>'value','0') AS score,(s.object_id IS NOT NULL) AS score_reported"
            + console.source().replace(" WHERE", " LEFT JOIN LATERAL (SELECT string_agg(DISTINCT g.data->>'model',', ' ORDER BY g.data->>'model') AS models,sum((g.data->'usage'->>'inputTokens')::numeric+(g.data->'usage'->>'outputTokens')::numeric) AS tokens,sum(g.cost) AS estimated_cost,count(*) AS generations,count(g.cost) AS priced_calls,count(*) FILTER(WHERE g.cost IS NULL) AS unknown_costs FROM objects g WHERE g.project_id=o.project_id AND g.trace_id=o.trace_id AND g.type='observation' AND g.data->>'observationType'='generation' AND g.started_at>workspace_retention_cutoff(w)) m ON true LEFT JOIN LATERAL (SELECT sc.data,sc.object_id FROM objects sc WHERE sc.project_id=o.project_id AND sc.type='score' AND sc.data->>'targetType'='trace' AND sc.data->>'targetId'=o.object_id AND sc.started_at>workspace_retention_cutoff(w) ORDER BY sc.started_at DESC,sc.object_id ASC LIMIT 1) s ON true WHERE")
            + " AND o.type='trace' AND (?='' OR o.data->>'status'=?) AND (?='' OR o.object_id ILIKE"
            + " ? OR o.data->>'name' ILIKE ?)"+traceWindow+costFilter,
        order,
        traceArgs.toArray(),
        zh
            ? new String[] {"名称", "eventId", "对象 ID", "耗时 ms", "用户", "客户", "状态", "★打分", "开始时间 UTC", "模型", "已知 Token", "已知估算费用 USD", "成本未知调用"}
            : new String[] {
              "Name", "eventId", "Object ID", "Duration ms", "User", "Customer", "Status", "Score", "Started UTC", "Models", "Known tokens", "Known estimated cost USD", "Unknown cost calls"
            },
        new float[] {120, 150, 130, 65, 80, 80, 65, 65, 100, 100, 80, 100, 75},
        r -> {
          var d = Support.parse(r.get("data").toString());
          return new String[] {
            d.path("name").asText(val(r.get("object_id"), "—")),
            val(r.get("event_id"), "—"),
            val(r.get("object_id"), "—"),
            d.path("durationMs").asText("—"),
            d.path("userId").asText("—"),
            d.path("customerId").asText("—"),
            d.path("status").asText("—"),val(r.get("score"),"0"),
            utc(r.get("started_at")),val(r.get("models"),"—"),val(r.get("tokens"),"—"),val(r.get("estimated_cost"),zh?"未知":"Unknown"),val(r.get("unknown_costs"),"0")
          };
        });
  }

  @GetMapping("/api/reports/{kind}")
  @Transactional(transactionManager = "readTransactionManager", readOnly = true, isolation = Isolation.REPEATABLE_READ)
  Map<String, Object> page(
      @PathVariable String kind,
      @RequestParam(required = false) UUID project,
      @RequestParam(defaultValue = "customerId") String dimension,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(required = false) Integer year,
      @RequestParam(required = false) Integer month,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "5") int size,
      HttpServletRequest req) {
    Support.require(page >= 0, "INVALID_PAGE");
    Support.require(Set.of(5, 10, 25, 50, 100).contains(size), "INVALID_PAGE_SIZE");
    var r = report(kind, project, dimension, search, status, false, year, month, req);
    long total =
        store.queries().queryForObject(
            "SELECT count(*) FROM (" + r.sql() + ") report_count", Long.class, r.args());
    var args = new ArrayList<>(Arrays.asList(r.args()));
    args.add(size);
    args.add((long) page * size);
    var items =
        store.queries().queryForList(
            "SELECT * FROM ("
                + r.sql()
                + ") report_rows ORDER BY "
                + r.order()
                + " LIMIT ? OFFSET ?",
            args.toArray());
    if (kind.equals("traces")) console.decode(items);
    var result=new LinkedHashMap<String,Object>();
    result.put("items",items);result.put("total",total);result.put("page",page);result.put("size",size);
    if(kind.equals("analytics")) {
      result.put("chart",store.queries().queryForList("SELECT dimension,tokens,cost FROM ("+r.sql()+") chart_rows ORDER BY tokens DESC NULLS LAST,dimension ASC NULLS LAST LIMIT 20",r.args()));
      result.put("chart_costs",store.queries().queryForList("SELECT dimension,tokens,cost FROM ("+r.sql()+") chart_rows ORDER BY cost DESC NULLS LAST,dimension ASC NULLS LAST LIMIT 20",r.args()));
      result.put("chart_limit",20);
    }
    return result;
  }

  Report traceExport(Report parent, String types, boolean zh) {
    var selected = new HashSet<>(Arrays.asList(types.split(",", -1)));
    Support.require(!selected.isEmpty() && Set.of("trace", "observation").containsAll(selected), "INVALID_EXPORT_TYPES");
    // Keep the filtered, ordered Trace set as the scope for both export types.
    // One query and one transaction preserve hierarchy across every exported page.
    String sql = "WITH parents AS (SELECT report_rows.*,row_number() OVER(ORDER BY " + parent.order()
        + ") AS export_parent FROM (" + parent.sql() + ") report_rows) "
        + "SELECT p.export_parent,0 AS export_child,p.type,p.data,p.event_id,p.object_id,p.started_at,p.tokens,p.estimated_cost,p.score,p.data->>'userId' AS user_id,p.data->>'customerId' AS customer_id FROM parents p WHERE "
        + selected.contains("trace")
        + " UNION ALL SELECT p.export_parent,1 AS export_child,g.type,g.data,g.event_id,g.object_id,g.started_at,"
        + "(g.data->'usage'->>'inputTokens')::numeric+(g.data->'usage'->>'outputTokens')::numeric AS tokens,g.cost AS estimated_cost,coalesce(s.data->>'value','0') AS score,g.data->>'provider' AS user_id,NULL AS customer_id "
        + "FROM parents p JOIN objects g ON g.project_id=p.project_id AND g.trace_id=p.trace_id AND g.type='observation' "
        + "JOIN projects gp ON gp.id=g.project_id JOIN workspaces w ON w.id=gp.workspace_id "
        + "LEFT JOIN LATERAL(SELECT sc.data FROM objects sc WHERE sc.project_id=g.project_id AND sc.type='score' AND sc.data->>'targetType'='observation' AND sc.data->>'targetId'=g.object_id AND sc.started_at>workspace_retention_cutoff(w) ORDER BY sc.started_at DESC,sc.object_id ASC LIMIT 1)s ON true "
        + "WHERE g.started_at>workspace_retention_cutoff(w) AND " + selected.contains("observation");
    return new Report(sql,"export_parent,export_child,started_at,object_id",parent.args(),
        zh ? new String[]{"类型","名称 / 模型名称","eventId","对象 ID","耗时 ms","Token","费用 USD","用户 / 客户 / 供应商","状态","打分","开始时间 UTC"}
           : new String[]{"Type","Name / model","eventId","Object ID","Duration ms","Token","Cost USD","User / Customer / Provider","Status","Score","Started UTC"},
        new float[]{65,110,180,200,65,65,85,110,65,50,120}, r -> {
          var d=Support.parse(r.get("data").toString());
          boolean trace="trace".equals(r.get("type"));
          return new String[]{trace?"Trace":"Observation",trace?d.path("name").asText("—"):d.path("model").asText(d.path("name").asText("—")),
              val(r.get("event_id"),"—"),val(r.get("object_id"),"—"),d.path("durationMs").asText("—"),val(r.get("tokens"),"—"),val(r.get("estimated_cost"),zh?"未知":"Unknown"),
              trace?val(r.get("user_id"),"—")+" / "+val(r.get("customer_id"),"—"):val(r.get("user_id"),"—"),d.path("status").asText("—"),val(r.get("score"),"0"),utc(r.get("started_at"))};
        });
  }

  @GetMapping("/api/reports/{kind}/export")
  @Transactional(transactionManager = "readTransactionManager", readOnly = true, isolation = Isolation.REPEATABLE_READ)
  void export(
      @PathVariable String kind,
      @RequestParam(required = false) UUID project,
      @RequestParam String format,
      @RequestParam(defaultValue = "customerId") String dimension,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(required = false) Integer year,
      @RequestParam(required = false) Integer month,
      @RequestParam(defaultValue = "en") String lang,
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    Support.require(Set.of("xlsx", "pdf").contains(format), "INVALID_FORMAT");
    var base = report(kind, project, dimension, search, status, lang.equals("zh-CN"), year, month, req);
    var r = kind.equals("traces") ? traceExport(base, Optional.ofNullable(req.getParameter("types")).orElse("trace,observation"), lang.equals("zh-CN")) : base;
    res.setContentType(
        format.equals("xlsx")
            ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            : "application/pdf");
    res.setHeader(
        "Content-Disposition", "attachment; filename=\"metertrail-" + kind + "." + format + "\"");
    res.setHeader("Cache-Control", "no-store");
    var excel =
        format.equals("xlsx") ? new ActivityExport.Excel(res.getOutputStream(), r.headers()) : null;
    var pdf = format.equals("pdf") ? new ActivityExport.Pdf(r.headers(), r.widths()) : null;
    try {
      store.queries().query(
          con -> {
            var stmt =
                con.prepareStatement(
                    "SELECT * FROM (" + r.sql() + ") report_rows ORDER BY " + r.order());
            for (int i = 0; i < r.args().length; i++) stmt.setObject(i + 1, r.args()[i]);
            stmt.setFetchSize(500);
            return stmt;
          },
          (org.springframework.jdbc.core.RowCallbackHandler)
              rs -> {
                var row = new HashMap<String, Object>();
                var meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++)
                  row.put(meta.getColumnLabel(i), rs.getObject(i));
                try {
                  var values = r.row().apply(row);
                  if (excel != null) excel.add(values);
                  else pdf.add(values);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
      if (pdf != null) pdf.save(res.getOutputStream());
    } finally {
      if (excel != null) excel.close();
      if (pdf != null) pdf.close();
    }
  }
}
