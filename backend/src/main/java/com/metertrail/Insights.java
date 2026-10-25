package com.metertrail;

import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Retained, latest-revision observations only. Never mixes monitoring charges with model cost. */
@Service
class Insights {
  final Store store;
  Insights(Store store) { this.store = store; }

  Map<String,Object> paid(UUID user, UUID project) {
    var p = store.project(user, project);
    var w = store.workspace(user);
    if ("Free".equals(w.get("plan"))) throw new ApiError(403,"INSIGHTS_PLAN_REQUIRED");
    return w;
  }

  static BigDecimal number(Object value) { return value == null ? null : new BigDecimal(value.toString()); }
  static BigDecimal divide(Object a, Object b) {
    BigDecimal x = number(a), y = number(b);
    return x == null || y == null || y.signum() == 0 ? null : x.divide(y,8,RoundingMode.HALF_UP);
  }
  static BigDecimal change(Object before, Object after) {
    BigDecimal a=number(before), b=number(after);
    return a==null || b==null || a.signum()==0 ? null : b.subtract(a).multiply(BigDecimal.valueOf(100)).divide(a,4,RoundingMode.HALF_UP);
  }
  static long count(Map<String,Object> m,String key) { return ((Number)m.get(key)).longValue(); }
  static boolean priced(Map<String,Object> m) { return count(m,"generations")>0 && count(m,"generations")==count(m,"priced_generations"); }
  static boolean grew(BigDecimal value) { return value!=null && value.compareTo(BigDecimal.valueOf(20))>=0; }

  Map<String,Object> summary(UUID project, Instant start, Instant end, Instant cutoff) {
    var m=store.queries().queryForMap("""
      SELECT count(*) FILTER(WHERE type='trace') AS requests,
        count(*) FILTER(WHERE type='trace' AND data->>'status'='error') AS errors,
        count(*) FILTER(WHERE type='trace' AND data->>'durationMs' IS NOT NULL) AS timed_requests,
        percentile_cont(0.95) WITHIN GROUP(ORDER BY (data->>'durationMs')::double precision)
          FILTER(WHERE type='trace' AND data->>'durationMs' IS NOT NULL) AS p95_ms,
        count(*) FILTER(WHERE type='observation' AND data->>'observationType'='generation') AS generations,
        count(*) FILTER(WHERE type='observation' AND data->>'observationType'='generation' AND cost IS NOT NULL) AS priced_generations,
        sum(cost) FILTER(WHERE type='observation' AND data->>'observationType'='generation') AS known_cost,
        count(*) FILTER(WHERE type='observation' AND data->>'observationType'='generation' AND data->'usage'->>'inputTokens' IS NOT NULL) AS usage_generations,
        sum((data->'usage'->>'inputTokens')::numeric) FILTER(WHERE type='observation' AND data->>'observationType'='generation') AS input_tokens,
        sum((data->'usage'->>'outputTokens')::numeric) FILTER(WHERE type='observation' AND data->>'observationType'='generation') AS output_tokens
      FROM objects WHERE project_id=? AND started_at>=? AND started_at<? AND started_at>=?
      """,project,Timestamp.from(start),Timestamp.from(end),Timestamp.from(cutoff));
    m.put("start",start);m.put("end",end);
    m.put("retention_complete",!start.isBefore(cutoff));
    m.put("price_coverage",divide(m.get("priced_generations"),m.get("generations")));
    m.put("error_rate",divide(m.get("errors"),m.get("requests")));
    m.put("average_model_cost",priced(m)?divide(m.get("known_cost"),m.get("generations")):null);
    m.put("average_input_tokens",count(m,"usage_generations")==count(m,"generations")?divide(m.get("input_tokens"),m.get("generations")):null);
    return m;
  }

  List<Map<String,Object>> contributors(UUID project,Instant start,Instant middle,Instant end,Instant cutoff,String dimension) {
    String key=switch(dimension) {
      case "model" -> "jsonb_build_array(data->>'provider',data->>'model')::text";
      case "feature" -> "data->>'feature'";
      case "customerId" -> "data->>'customerId'";
      default -> throw new ApiError(400,"INVALID_DIMENSION");
    };
    return store.queries().queryForList("SELECT "+key+" AS label,count(*) FILTER(WHERE started_at>=?) AS current_calls,"
      +"sum(cost) FILTER(WHERE started_at>=?) AS current_cost,sum(cost) FILTER(WHERE started_at<?) AS previous_cost,"
      +"count(*) FILTER(WHERE started_at>=? AND cost IS NULL) AS unknown_costs FROM objects"
      +" WHERE project_id=? AND type='observation' AND data->>'observationType'='generation'"
      +" AND started_at>=? AND started_at<? AND started_at>=? GROUP BY 1"
      +" ORDER BY sum(cost) FILTER(WHERE started_at>=?) DESC NULLS LAST,1 NULLS LAST LIMIT 50",
      Timestamp.from(middle),Timestamp.from(middle),Timestamp.from(middle),Timestamp.from(middle),project,
      Timestamp.from(start),Timestamp.from(end),Timestamp.from(cutoff),Timestamp.from(middle));
  }

  static Map<String,Object> comparison(Map<String,Object> before,Map<String,Object> after) {
    var c=new LinkedHashMap<String,Object>();
    boolean retained=Boolean.TRUE.equals(before.get("retention_complete")) && Boolean.TRUE.equals(after.get("retention_complete"));
    c.put("minimum_sample",20);c.put("retention_complete",retained);
    c.put("sufficient_requests",retained && count(before,"requests")>=20 && count(after,"requests")>=20);
    c.put("sufficient_generations",retained && count(before,"generations")>=20 && count(after,"generations")>=20);
    c.put("volume_change_pct",retained?change(before.get("generations"),after.get("generations")):null);
    c.put("cost_change_pct",retained && priced(before) && priced(after)?change(before.get("known_cost"),after.get("known_cost")):null);
    c.put("average_cost_change_pct",retained?change(before.get("average_model_cost"),after.get("average_model_cost")):null);
    c.put("input_change_pct",retained?change(before.get("average_input_tokens"),after.get("average_input_tokens")):null);
    c.put("p95_change_pct",retained && count(before,"timed_requests")>=20 && count(after,"timed_requests")>=20?change(before.get("p95_ms"),after.get("p95_ms")):null);
    c.put("error_rate_change_pp",retained && before.get("error_rate")!=null && after.get("error_rate")!=null?
      number(after.get("error_rate")).subtract(number(before.get("error_rate"))).multiply(BigDecimal.valueOf(100)):null);
    var findings=new ArrayList<String>();
    if(!retained) findings.add("RETENTION_INCOMPLETE");
    if(count(after,"requests")==0 && count(after,"generations")==0) findings.add("NO_DATA");
    if(count(before,"generations")>count(before,"priced_generations") || count(after,"generations")>count(after,"priced_generations")) findings.add("MISSING_PRICES");
    if(count(before,"generations")<20 || count(after,"generations")<20) findings.add("LOW_SAMPLE");
    if(Boolean.TRUE.equals(c.get("sufficient_generations")) && grew((BigDecimal)c.get("cost_change_pct"))) findings.add("COST_GROWTH");
    if(Boolean.TRUE.equals(c.get("sufficient_generations")) && grew((BigDecimal)c.get("average_cost_change_pct"))) findings.add("UNIT_COST_GROWTH");
    if(Boolean.TRUE.equals(c.get("sufficient_generations")) && grew((BigDecimal)c.get("input_change_pct"))) findings.add("INPUT_GROWTH");
    if(count(after,"requests")>=20 && number(after.get("error_rate")).compareTo(new BigDecimal("0.05"))>=0) findings.add("ERRORS");
    if(grew((BigDecimal)c.get("p95_change_pct"))) findings.add("LATENCY_GROWTH");
    c.put("findings",findings);
    return c;
  }

  @Transactional(transactionManager="readTransactionManager",propagation=Propagation.REQUIRES_NEW,readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
  public Map<String,Object> analyze(UUID user,UUID project,Instant start,Instant middle,Instant end,String dimension) {
    var w=paid(user,project);
    Instant now=Instant.now(),cutoff=store.queries().queryForObject("SELECT workspace_retention_cutoff(w) FROM workspaces w WHERE id=?",Timestamp.class,w.get("id")).toInstant();
    Support.require(start.isBefore(middle) && middle.isBefore(end) && !end.isAfter(now.plusSeconds(1))
      && Duration.between(start,end).compareTo(Duration.ofDays(28))<=0,"INVALID_INSIGHT_WINDOW");
    var before=summary(project,start,middle,cutoff);var after=summary(project,middle,end,cutoff);
    var result=new LinkedHashMap<String,Object>();
    result.put("before",before);result.put("after",after);result.put("comparison",comparison(before,after));
    result.put("dimension",dimension);result.put("contributors",contributors(project,start,middle,end,cutoff,dimension));
    result.put("samples",store.queries().queryForList("SELECT object_id,trace_id,data->>'name' AS name,cost,started_at FROM objects"
      +" WHERE project_id=? AND type='observation' AND data->>'observationType'='generation' AND cost IS NOT NULL"
      +" AND started_at>=? AND started_at<? AND started_at>=? ORDER BY cost DESC,object_id LIMIT 10",
      project,Timestamp.from(middle),Timestamp.from(end),Timestamp.from(cutoff)));
    return result;
  }
}
