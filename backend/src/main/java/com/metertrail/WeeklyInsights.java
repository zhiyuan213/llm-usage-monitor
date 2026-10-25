package com.metertrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class WeeklyInsights {
  final Store store;final Insights insights;final ObjectMapper json;final TransactionTemplate tx;final boolean enabled;final String publicUrl;
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(WeeklyInsights.class);
  WeeklyInsights(Store store,Insights insights,ObjectMapper json,
      @Qualifier("transactionManager") PlatformTransactionManager manager,
      @Value("${metertrail.worker-enabled:true}") boolean enabled,
      @Value("${metertrail.public-url}") String publicUrl) {
    this.store=store;this.insights=insights;this.json=json;this.tx=new TransactionTemplate(manager);this.enabled=enabled;
    this.publicUrl=publicUrl.replaceAll("/+$","");
  }
  static ZoneId zone(String value) {
    try {return ZoneId.of(value);} catch(Exception e) {throw new ApiError(400,"INVALID_TIMEZONE");}
  }
  static Instant weekEnd(Instant now,ZoneId zone) {
    return now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant();
  }
  static Instant nextDue(Instant now,ZoneId zone) {
    var monday=now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atTime(9,0).atZone(zone);
    if(!monday.toInstant().isAfter(now))monday=monday.plusWeeks(1);
    return monday.toInstant();
  }
  static boolean mailEligible(Store store,Object outboxId) {
    return Boolean.TRUE.equals(store.queries().queryForObject("""
      SELECT EXISTS(SELECT 1 FROM outbox original WHERE original.id=? AND (original.insight_project_id IS NULL OR EXISTS(
        SELECT 1 FROM insight_weekly_reports r
        JOIN insight_weekly_subscriptions s ON s.project_id=r.project_id
        JOIN projects p ON p.id=r.project_id JOIN workspaces w ON w.id=p.workspace_id
        JOIN users u ON u.id=w.owner_id JOIN outbox o ON o.id=r.outbox_id
        WHERE r.outbox_id=? AND r.cancelled_at IS NULL AND s.enabled AND p.deleted_at IS NULL
          AND u.deleted_at IS NULL AND u.verified AND u.email=o.recipient AND w.plan IN ('Core','Pro'))))
      """,Boolean.class,outboxId,outboxId));
  }

  @Scheduled(scheduler="weeklyInsightsScheduler",fixedDelay=60000)
  public void schedule() {
    if(!enabled)return;
    Instant now=Instant.now();
    var due=store.read.queryForList("SELECT project_id FROM insight_weekly_subscriptions WHERE enabled AND next_due<=? ORDER BY next_due LIMIT 20",Timestamp.from(now));
    for(var row:due) {
      try {generate((UUID)row.get("project_id"),now);}
      catch(RuntimeException e) {log.warn("Weekly insight generation failed for project {}; will retry",row.get("project_id"));}
    }
  }

  boolean generate(UUID project,Instant now) {
    return generate(project,now,false);
  }
  boolean sendNow(UUID project,Instant now) {
    return generate(project,now,true);
  }
  private boolean generate(UUID project,Instant now,boolean manual) {
    return Boolean.TRUE.equals(tx.execute(status->{
      var rows=store.db.queryForList("SELECT s.*,u.id AS owner,u.email,u.language,u.verified,u.deleted_at AS user_deleted,p.deleted_at AS project_deleted,p.name,w.plan FROM insight_weekly_subscriptions s JOIN projects p ON p.id=s.project_id JOIN workspaces w ON w.id=p.workspace_id JOIN users u ON u.id=w.owner_id WHERE s.project_id=? AND s.enabled AND (? OR s.next_due<=?) FOR UPDATE OF s"+(manual?"":" SKIP LOCKED"),project,manual,Timestamp.from(now));
      if(rows.isEmpty()) {if(manual)throw new ApiError(409,"WEEKLY_NOT_ENABLED");return false;}
      var row=rows.get(0);var zone=zone(row.get("timezone").toString());
      if(row.get("user_deleted")!=null || row.get("project_deleted")!=null || !Boolean.TRUE.equals(row.get("verified")) || "Free".equals(row.get("plan"))) {
        if(manual)throw new ApiError(403,"INSIGHTS_PLAN_REQUIRED");
        store.db.update("UPDATE insight_weekly_subscriptions SET enabled=false,updated_at=now() WHERE project_id=?",project);return false;
      }
      Instant end=weekEnd(now,zone);
      boolean exists=Boolean.TRUE.equals(store.db.queryForObject("SELECT EXISTS(SELECT 1 FROM insight_weekly_reports WHERE project_id=? AND week_end=?)",Boolean.class,project,Timestamp.from(end)));
      if(!exists) {
        var report=new LinkedHashMap<>(insights.analyze((UUID)row.get("owner"),project,end.atZone(zone).minusWeeks(2).toInstant(),end.atZone(zone).minusWeeks(1).toInstant(),end,"feature"));
        report.remove("samples");
        boolean zh="zh-CN".equals(row.get("language"));
        String body=render(row.get("name").toString(),report,project,zh,publicUrl);
        Long outboxId=store.db.queryForObject("INSERT INTO outbox(recipient,subject,body,insight_project_id) VALUES(?,?,?,?) RETURNING id",Long.class,row.get("email"),zh?"MeterTrail 运营周报":"MeterTrail weekly report",store.secrets.seal(body),project);
        try {store.db.update("INSERT INTO insight_weekly_reports(id,project_id,week_end,summary,outbox_id) VALUES(?,?,?,?::jsonb,?)",UUID.randomUUID(),project,Timestamp.from(end),json.writeValueAsString(report),outboxId);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException(e);}
      }
      if(!manual)store.db.update("UPDATE insight_weekly_subscriptions SET next_due=? WHERE project_id=?",Timestamp.from(nextDue(now,zone)),project);
      return !exists;
    }));
  }

  @SuppressWarnings("unchecked")
  static String render(String projectName,Map<String,Object> report,UUID project,boolean zh,String publicUrl) {
    var before=(Map<String,Object>)report.get("before");var after=(Map<String,Object>)report.get("after");var comparison=(Map<String,Object>)report.get("comparison");
    var lines=new ArrayList<String>();
    lines.add((zh?"项目：":"Project: ")+projectName);
    lines.add((zh?"统计区间（起点包含，终点不包含）：":"Window (inclusive start, exclusive end): ")+after.get("start")+" → "+after.get("end"));
    lines.add((zh?"请求数：":"Requests: ")+after.get("requests")+(zh?"；失败：":"; errors: ")+after.get("errors"));
    lines.add((zh?"模型调用数：":"Model calls: ")+after.get("generations")+(zh?"；已定价：":"; priced: ")+after.get("priced_generations"));
    lines.add((zh?"已知模型估算费用（USD）：":"Known estimated model cost (USD): ")+value(after.get("known_cost"),zh));
    lines.add((zh?"上期已知费用（USD）：":"Previous known cost (USD): ")+value(before.get("known_cost"),zh));
    lines.add((zh?"每次模型调用均价（仅价格完整时）：":"Average model call cost (complete prices only): ")+value(after.get("average_model_cost"),zh));
    lines.add((zh?"P95 请求耗时（毫秒）：":"P95 request duration (ms): ")+value(after.get("p95_ms"),zh));
    lines.add((zh?"费用变化（%）：":"Cost change (%): ")+value(comparison.get("cost_change_pct"),zh));
    lines.add(zh?"发现与建议：":"Observations and next steps:");
    if(((List<?>)comparison.get("findings")).isEmpty())lines.add(zh?"现有数据未触发规则阈值。":"No configured threshold was crossed in the available data.");
    for(String finding:(List<String>)comparison.get("findings")) lines.add("- "+switch(finding) {
      case "COST_GROWTH" -> zh?"费用增长至少 20%，请结合调用量与均价查看。":"Cost grew at least 20%; review volume and average cost together.";
      case "UNIT_COST_GROWTH" -> zh?"模型调用均价增长至少 20%，建议检查模型组合、价格和用量。":"Average call cost grew at least 20%; check model mix, pricing and usage.";
      case "INPUT_GROWTH" -> zh?"平均输入长度增长至少 20%，可检查上下文长度；不能据此认定根因。":"Average input length grew at least 20%; review context size without assuming causation.";
      case "ERRORS" -> zh?"请求失败比例达到 5%，建议查看失败调用。":"At least 5% of requests failed; inspect failed traces.";
      case "LATENCY_GROWTH" -> zh?"P95 延迟增长至少 20%，建议检查慢调用。":"P95 latency grew at least 20%; inspect slow traces.";
      case "MISSING_PRICES" -> zh?"有调用缺少价格，请先补齐模型价格。":"Some model calls lack prices; configure missing model prices.";
      case "RETENTION_INCOMPLETE" -> zh?"部分历史已超出保留期，对比不完整。":"The historical period is not fully retained.";
      case "NO_DATA" -> zh?"本周暂无已上报请求或模型调用。":"No requests or model calls were reported for this week.";
      default -> zh?"至少一个区间模型调用不足 20 次，变化仅供初步观察。":"One period has fewer than 20 model calls; changes are preliminary.";
    });
    lines.add((zh?"费用最高的功能（已知费用，最多 5 组）：":"Top features by known cost (up to 5):"));
    var groups=(List<Map<String,Object>>)report.get("contributors");
    groups.stream().limit(5).forEach(g->lines.add(value(g.get("label"),zh)+": "+value(g.get("current_cost"),zh)+" USD"));
    lines.add(zh?"注意：缺失价格不按零费用处理；样本较少或历史数据不完整时，变化不能作为确定结论。以上为模型费用估算，不含 MeterTrail 订阅费，也不包含收入或利润。":"Missing prices are not zero cost. Small samples or incomplete retention limit conclusions. These are model cost estimates, not MeterTrail charges, revenue or profit.");
    lines.add(publicUrl+"/app/cost-insights?project="+project);
    lines.add(zh?"可在控制台的“自动周报”中关闭订阅。":"Unsubscribe from Weekly reports in your console.");
    lines.add(publicUrl+"/app/weekly?project="+project);
    return String.join("\n",lines);
  }
  static String value(Object value,boolean zh) {
    if(value==null)return zh?"未知 / 不适用":"Unknown / not applicable";
    return value instanceof Number?new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString():value.toString();
  }

  @Scheduled(scheduler="weeklyInsightsScheduler",fixedDelay=3600000)
  public void cleanup() {
    if(!enabled)return;
    store.db.update("DELETE FROM insight_weekly_reports WHERE id IN (SELECT r.id FROM insight_weekly_reports r JOIN projects p ON p.id=r.project_id JOIN workspaces w ON w.id=p.workspace_id JOIN users u ON u.id=w.owner_id JOIN outbox o ON o.id=r.outbox_id WHERE (r.created_at<now()-interval '90 days' OR p.deleted_at IS NOT NULL OR u.deleted_at IS NOT NULL) AND (o.sent_at IS NOT NULL OR o.attempts>=10) ORDER BY r.created_at LIMIT 500)");
  }
}
