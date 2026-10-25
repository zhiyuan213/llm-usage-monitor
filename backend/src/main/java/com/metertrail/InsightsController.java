package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class InsightsController {
  final Store store; final Insights insights; final RateLimits limits; final WeeklyInsights weekly;
  InsightsController(Store store,Insights insights,RateLimits limits,WeeklyInsights weekly) {this.store=store;this.insights=insights;this.limits=limits;this.weekly=weekly;}
  static Instant timestamp(String value) {
    try {return Instant.parse(value);} catch(Exception e) {throw new ApiError(400,"INVALID_TIMESTAMP");}
  }
  static void pagination(int page,int size) {Support.require(page>=0 && page<=100000 && Set.of(5,10,50,100).contains(size),"INVALID_PAGE");}

  @GetMapping("/api/projects/{project}/insights/costs")
  Map<String,Object> costs(@PathVariable UUID project,@RequestParam(defaultValue="7") int days,
      @RequestParam(defaultValue="feature") String dimension,HttpServletRequest req) {
    Support.require(Set.of(1,7,14).contains(days),"INVALID_INSIGHT_WINDOW");
    limits.check("insight-read:"+SessionFilter.uid(req),30);
    Instant end=Instant.now(),middle=end.minus(Duration.ofDays(days));
    return insights.analyze(SessionFilter.uid(req),project,middle.minus(Duration.ofDays(days)),middle,end,dimension);
  }

  @GetMapping("/api/projects/{project}/insights/releases")
  @Transactional(transactionManager="readTransactionManager",readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  Map<String,Object> releases(@PathVariable UUID project,@RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="5") int size,HttpServletRequest req) {
    insights.paid(SessionFilter.uid(req),project);pagination(page,size);
    return Map.of("total",store.queries().queryForObject("SELECT count(*) FROM insight_releases WHERE project_id=?",Long.class,project),
      "items",store.queries().queryForList("SELECT id,name,released_at,created_at FROM insight_releases WHERE project_id=? ORDER BY released_at DESC,id LIMIT ? OFFSET ?",project,size,(long)page*size));
  }

  @PostMapping("/api/projects/{project}/insights/releases")
  @Transactional
  Map<String,Object> release(@PathVariable UUID project,@RequestBody JsonNode body,HttpServletRequest req) {
    var user=SessionFilter.uid(req);var w=insights.paid(user,project);
    limits.check("insight-release:"+user,10);
    var name=Support.text(body,"name",240).trim();Instant at=timestamp(body.path("releasedAt").asText());
    Support.require(!at.isAfter(Instant.now()) && !at.isBefore(Instant.now().minus(Duration.ofDays(Billing.retention(w.get("plan").toString())))),"INVALID_RELEASE_TIME");
    var id=store.db.queryForObject("INSERT INTO insight_releases(id,project_id,name,released_at) VALUES(?,?,?,?) ON CONFLICT(project_id,name,released_at) DO UPDATE SET name=excluded.name RETURNING id",UUID.class,UUID.randomUUID(),project,name,Timestamp.from(at));
    store.audit(user,(UUID)w.get("id"),"release.create",id.toString(),true,req);
    return Map.of("id",id);
  }

  @PostMapping("/api/projects/{project}/insights/releases/{release}/delete")
  @Transactional
  Map<String,String> delete(@PathVariable UUID project,@PathVariable UUID release,HttpServletRequest req) {
    var user=SessionFilter.uid(req);var w=insights.paid(user,project);
    int n=store.db.update("DELETE FROM insight_releases WHERE project_id=? AND id=?",project,release);
    if(n==0)throw new ApiError(404,"RELEASE_NOT_FOUND");
    store.audit(user,(UUID)w.get("id"),"release.delete",release.toString(),true,req);
    return Map.of("status","deleted");
  }

  @GetMapping("/api/projects/{project}/insights/releases/{release}")
  Map<String,Object> compare(@PathVariable UUID project,@PathVariable UUID release,
      @RequestParam(defaultValue="24") int hours,HttpServletRequest req) {
    insights.paid(SessionFilter.uid(req),project);Support.require(Set.of(24,72,168).contains(hours),"INVALID_INSIGHT_WINDOW");
    limits.check("insight-read:"+SessionFilter.uid(req),30);
    var markers=store.queries().queryForList("SELECT name,released_at FROM insight_releases WHERE id=? AND project_id=?",release,project);
    if(markers.isEmpty())throw new ApiError(404,"RELEASE_NOT_FOUND");
    Instant at=((Timestamp)markers.get(0).get("released_at")).toInstant();
    Duration elapsed=Duration.between(at,Instant.now()),window=Duration.ofHours(hours);
    Support.require(!elapsed.isNegative() && !elapsed.isZero(),"INVALID_RELEASE_TIME");
    Duration effective=elapsed.compareTo(window)<0?elapsed:window;
    var result=insights.analyze(SessionFilter.uid(req),project,at.minus(effective),at,at.plus(effective),"feature");
    result.put("release",markers.get(0));result.put("window_complete",elapsed.compareTo(window)>=0);
    result.put("requested_hours",hours);result.put("effective_seconds",effective.toSeconds());return result;
  }

  @GetMapping("/api/projects/{project}/insights/weekly")
  @Transactional(transactionManager="readTransactionManager",readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  Map<String,Object> settings(@PathVariable UUID project,@RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="5") int size,HttpServletRequest req) {
    UUID user=SessionFilter.uid(req);store.project(user,project);pagination(page,size);var w=store.workspace(user);
    var settings=store.queries().queryForList("SELECT enabled,timezone,next_due FROM insight_weekly_subscriptions WHERE project_id=?",project);
    String where=" FROM insight_weekly_reports r JOIN outbox o ON o.id=r.outbox_id WHERE r.project_id=? AND r.created_at>=?";
    Timestamp cutoff=Timestamp.from(Instant.now().minus(Duration.ofDays(Math.min(90,Billing.retention(w.get("plan").toString())))));
    return Map.of("available",!"Free".equals(w.get("plan")),"subscription",settings.isEmpty()?Map.of("enabled",false,"timezone","UTC"):settings.get(0),
      "total",store.queries().queryForObject("SELECT count(*)"+where,Long.class,project,cutoff),
      "items",store.queries().queryForList("SELECT r.id,r.week_end,r.created_at,r.test_email,o.attempts,CASE WHEN r.cancelled_at IS NOT NULL THEN 'cancelled' WHEN o.sent_at IS NOT NULL THEN 'sent' WHEN o.attempts>=10 THEN 'failed' ELSE 'queued' END AS status"+where+" ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?",project,cutoff,size,(long)page*size));
  }

  @PostMapping("/api/projects/{project}/insights/weekly")
  @Transactional
  Map<String,Object> subscribe(@PathVariable UUID project,@RequestBody JsonNode body,HttpServletRequest req) {
    UUID user=SessionFilter.uid(req);var p=store.project(user,project);
    Support.require(body.path("enabled").isBoolean(),"INVALID_WEEKLY_SETTINGS");
    boolean enabled=body.path("enabled").asBoolean();if(enabled)insights.paid(user,project);
    ZoneId zone=WeeklyInsights.zone(body.path("timezone").asText("UTC"));
    var account=SessionFilter.user(req);Support.require(!enabled || Boolean.TRUE.equals(account.get("verified")),"EMAIL_NOT_VERIFIED");
    store.db.update("INSERT INTO insight_weekly_subscriptions(project_id,enabled,timezone,next_due) VALUES(?,?,?,?) ON CONFLICT(project_id) DO UPDATE SET enabled=excluded.enabled,timezone=excluded.timezone,next_due=excluded.next_due,updated_at=now()",project,enabled,zone.getId(),Timestamp.from(WeeklyInsights.nextDue(Instant.now(),zone)));
    if(!enabled) {
      store.db.update("UPDATE outbox SET attempts=10,body='' WHERE sent_at IS NULL AND id IN (SELECT outbox_id FROM insight_weekly_reports WHERE project_id=?)",project);
      store.db.update("UPDATE insight_weekly_reports r SET cancelled_at=now() FROM outbox o WHERE r.project_id=? AND r.outbox_id=o.id AND o.sent_at IS NULL",project);
    }
    store.audit(user,(UUID)p.get("workspace_id"),"weekly.subscription",project.toString(),true,req);
    return Map.of("enabled",enabled);
  }

  @GetMapping("/api/projects/{project}/insights/weekly/preview")
  Map<String,Object> preview(@PathVariable UUID project,@RequestParam(required=false) String timezone,HttpServletRequest req) {
    UUID user=SessionFilter.uid(req);insights.paid(user,project);
    limits.check("insight-read:"+user,30);
    var zones=store.queries().queryForList("SELECT timezone FROM insight_weekly_subscriptions WHERE project_id=?",project);
    ZoneId zone=WeeklyInsights.zone(timezone!=null?timezone:zones.isEmpty()?"UTC":zones.get(0).get("timezone").toString());
    var end=WeeklyInsights.weekEnd(Instant.now(),zone);
    return insights.analyze(user,project,end.atZone(zone).minusWeeks(2).toInstant(),end.atZone(zone).minusWeeks(1).toInstant(),end,"feature");
  }

  @GetMapping("/api/projects/{project}/insights/weekly/example")
  Map<String,Object> example(@PathVariable UUID project,HttpServletRequest req) {
    store.project(SessionFilter.uid(req),project);return WeeklyInsights.example();
  }

  @PostMapping("/api/projects/{project}/insights/weekly/test")
  @Transactional
  Map<String,String> test(@PathVariable UUID project,HttpServletRequest req) {
    UUID user=SessionFilter.uid(req);var workspace=insights.paid(user,project);
    Support.require(Boolean.TRUE.equals(SessionFilter.user(req).get("verified")),"EMAIL_NOT_VERIFIED");
    limits.check("insight-send:"+user,3);
    boolean queued=weekly.sendTest(project,Instant.now());
    store.audit(user,(UUID)workspace.get("id"),"weekly.test",project.toString(),true,req);
    return Map.of("status",queued?"queued":"cooldown");
  }

  @PostMapping("/api/projects/{project}/insights/weekly/send")
  @Transactional
  Map<String,String> send(@PathVariable UUID project,HttpServletRequest req) {
    UUID user=SessionFilter.uid(req);var workspace=insights.paid(user,project);
    Support.require(Boolean.TRUE.equals(SessionFilter.user(req).get("verified")),"EMAIL_NOT_VERIFIED");
    limits.check("insight-send:"+user,3);
    boolean queued=weekly.sendNow(project,Instant.now());
    store.audit(user,(UUID)workspace.get("id"),"weekly.send",project.toString(),true,req);
    return Map.of("status",queued?"queued":"already_generated");
  }

  @GetMapping("/api/projects/{project}/insights/weekly/reports/{report}")
  com.fasterxml.jackson.databind.JsonNode savedReport(@PathVariable UUID project,@PathVariable UUID report,HttpServletRequest req) {
    UUID user=SessionFilter.uid(req);store.project(user,project);var w=store.workspace(user);
    var rows=store.queries().queryForList("SELECT summary FROM insight_weekly_reports WHERE id=? AND project_id=? AND created_at>=?",report,project,Timestamp.from(Instant.now().minus(Duration.ofDays(Math.min(90,Billing.retention(w.get("plan").toString()))))));
    if(rows.isEmpty())throw new ApiError(404,"REPORT_NOT_FOUND");
    var result=(com.fasterxml.jackson.databind.node.ObjectNode)Support.parse(rows.get(0).get("summary").toString());
    result.put("archived",true);return result;
  }
}
