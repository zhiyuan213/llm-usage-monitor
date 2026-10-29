package com.metertrail;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
class ReliabilityInsights {
  final Store store;final Insights insights;
  ReliabilityInsights(Store store,Insights insights) {this.store=store;this.insights=insights;}

  @Transactional(transactionManager="readTransactionManager",readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
  public Map<String,Object> analyze(UUID user,UUID project,int days,String scope,String dimension,String view,long slowMs,int page,int size) {
    var workspace=insights.paid(user,project);
    Support.require(Set.of(1,7,14).contains(days) && Set.of("trace","generation").contains(scope)
      && Set.of("feature","model").contains(dimension) && Set.of("errors","slow").contains(view)
      && slowMs>=500 && slowMs<=600000,"INVALID_RELIABILITY_FILTER");
    InsightsController.pagination(page,size);
    if(!"Pro".equals(workspace.get("plan")) && slowMs!=5000)throw new ApiError(403,"PRO_CUSTOM_RULES_REQUIRED");
    Instant end=Instant.now(),start=end.minus(Duration.ofDays(days));
    String where=" FROM objects WHERE project_id=? AND started_at>=? AND started_at<? AND "+
      (scope.equals("trace")?"type='trace'":"type='observation' AND data->>'observationType'='generation'");
    Object[] range={project,Timestamp.from(start),Timestamp.from(end)};
    var summary=store.queries().queryForMap("SELECT count(*) AS total,count(*) FILTER(WHERE data->>'status'='error') AS errors,"
      +"count(*) FILTER(WHERE data->>'status'='cancelled') AS cancelled,count(*) FILTER(WHERE data->>'durationMs' IS NOT NULL) AS timed,"
      +"count(*) FILTER(WHERE (data->>'durationMs')::numeric>=?) AS slow,"
      +"percentile_cont(0.5) WITHIN GROUP(ORDER BY (data->>'durationMs')::double precision) AS p50_ms,"
      +"percentile_cont(0.95) WITHIN GROUP(ORDER BY (data->>'durationMs')::double precision) AS p95_ms,"
      +"percentile_cont(0.99) WITHIN GROUP(ORDER BY (data->>'durationMs')::double precision) AS p99_ms"+where,
      slowMs,range[0],range[1],range[2]);
    summary.put("error_rate",Insights.divide(summary.get("errors"),summary.get("total")));
    summary.put("slow_rate",Insights.divide(summary.get("slow"),summary.get("timed")));
    summary.put("timing_coverage",Insights.divide(summary.get("timed"),summary.get("total")));
    String key=dimension.equals("model")?"jsonb_build_array(data->>'provider',data->>'model')::text":"data->>'feature'";
    String order=view.equals("errors")?"(count(*) FILTER(WHERE data->>'status'='error'))::numeric / NULLIF(count(*),0) DESC NULLS LAST,errors DESC,total DESC":"p95_ms DESC NULLS LAST,slow DESC,total DESC";
    var groups=store.queries().queryForList("SELECT "+key+" AS label,count(*) AS total,count(*) FILTER(WHERE data->>'status'='error') AS errors,"
      +"count(*) FILTER(WHERE (data->>'durationMs')::numeric>=?) AS slow,"
      +"percentile_cont(0.95) WITHIN GROUP(ORDER BY (data->>'durationMs')::double precision) AS p95_ms"
      +where+" GROUP BY 1 ORDER BY "+order+",1 NULLS LAST LIMIT 50",slowMs,range[0],range[1],range[2]);
    groups.forEach(g->g.put("error_rate",Insights.divide(g.get("errors"),g.get("total"))));
    var errorGroups=store.queries().queryForList("SELECT nullif(data->>'error','') AS message,count(*) AS count"+where
      +" AND data->>'status'='error' GROUP BY 1 ORDER BY count(*) DESC,1 NULLS LAST LIMIT 50",range);
    String predicate=view.equals("errors")?"data->>'status'='error'":"(data->>'durationMs')::numeric>=?";
    var args=new ArrayList<Object>(List.of(range));if(view.equals("slow"))args.add(slowMs);
    Long total=store.queries().queryForObject("SELECT count(*)"+where+" AND "+predicate,Long.class,args.toArray());
    var listArgs=new ArrayList<>(args);listArgs.add(size);listArgs.add((long)page*size);
    var items=store.queries().queryForList("SELECT object_id,trace_id,data->>'name' AS name,data->>'status' AS status,"
      +"data->>'error' AS error,data->>'provider' AS provider,data->>'model' AS model,data->>'feature' AS feature,"
      +"(data->>'durationMs')::numeric AS duration_ms,started_at"+where+" AND "+predicate+" ORDER BY "
      +(view.equals("slow")?"(data->>'durationMs')::numeric DESC,":"")+"started_at DESC,object_id LIMIT ? OFFSET ?",listArgs.toArray());
    var result=new LinkedHashMap<String,Object>();result.put("summary",summary);result.put("groups",groups);result.put("error_groups",errorGroups);
    result.put("items",items);result.put("total",total);result.put("start",start);result.put("end",end);
    result.put("scope",scope);result.put("dimension",dimension);result.put("slow_ms",slowMs);result.put("custom_rules","Pro".equals(workspace.get("plan")));
    result.put("view",view);
    return result;
  }
}
