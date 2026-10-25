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
  static Map<String,Object> example() {
    return Map.of("example",true,
      "before",Map.of("known_cost",10),
      "after",Map.of("start","2026-10-12T00:00:00Z","end","2026-10-19T00:00:00Z",
        "requests",1000,"errors",10,"generations",1200,"priced_generations",1200,
        "known_cost",12,"average_model_cost",0.01,"p95_ms",850),
      "comparison",Map.of("cost_change_pct",20,"findings",List.of("COST_GROWTH")),
      "contributors",List.of(Map.of("label","Example assistant","current_cost",12)));
  }
  boolean sendTest(UUID project,Instant now) {
    return Boolean.TRUE.equals(tx.execute(status->{
      var rows=store.db.queryForList("SELECT s.*,u.email,u.language,u.verified,u.deleted_at AS user_deleted,p.deleted_at AS project_deleted,p.name,w.plan FROM insight_weekly_subscriptions s JOIN projects p ON p.id=s.project_id JOIN workspaces w ON w.id=p.workspace_id JOIN users u ON u.id=w.owner_id WHERE s.project_id=? AND s.enabled FOR UPDATE OF s",project);
      if(rows.isEmpty())throw new ApiError(409,"WEEKLY_NOT_ENABLED");
      var row=rows.get(0);
      if(row.get("user_deleted")!=null||row.get("project_deleted")!=null||!Boolean.TRUE.equals(row.get("verified"))||!Set.of("Core","Pro").contains(row.get("plan")))throw new ApiError(403,"INSIGHTS_PLAN_REQUIRED");
      if(Boolean.TRUE.equals(store.db.queryForObject("SELECT EXISTS(SELECT 1 FROM insight_weekly_reports WHERE project_id=? AND test_email AND created_at>?)",Boolean.class,project,Timestamp.from(now.minusSeconds(60)))))return false;
      boolean zh="zh-CN".equals(row.get("language"));var report=example();
      String body=render(row.get("name").toString(),report,project,zh,publicUrl);
      Long outbox=store.db.queryForObject("INSERT INTO outbox(recipient,subject,body,insight_project_id) VALUES(?,?,?,?) RETURNING id",Long.class,row.get("email"),zh?"[TEST] MeterTrail 周报测试":"[TEST] MeterTrail weekly report test",store.secrets.seal(body),project);
      try {store.db.update("INSERT INTO insight_weekly_reports(id,project_id,week_end,summary,outbox_id,test_email) VALUES(?,?,?,?::jsonb,?,true)",UUID.randomUUID(),project,Timestamp.from(now),json.writeValueAsString(report),outbox);}
      catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
      return true;
    }));
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
      boolean exists=Boolean.TRUE.equals(store.db.queryForObject("SELECT EXISTS(SELECT 1 FROM insight_weekly_reports WHERE project_id=? AND week_end=? AND NOT test_email)",Boolean.class,project,Timestamp.from(end)));
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
    return WeeklyEmail.text(WeeklyEmail.values(projectName,report,project,publicUrl));
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
