package com.metertrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class BudgetAlerts {
  final Store store;final Insights insights;final ObjectMapper json;final TransactionTemplate writeTx,readTx;
  final boolean enabled;final String publicUrl;
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BudgetAlerts.class);
  BudgetAlerts(Store store,Insights insights,ObjectMapper json,
      @Qualifier("transactionManager") PlatformTransactionManager writer,
      @Qualifier("readTransactionManager") PlatformTransactionManager reader,
      @Value("${metertrail.worker-enabled:true}") boolean enabled,@Value("${metertrail.public-url}") String publicUrl) {
    this.store=store;this.insights=insights;this.json=json;this.enabled=enabled;this.publicUrl=publicUrl.replaceAll("/+$","");
    writeTx=new TransactionTemplate(writer);writeTx.setTimeout(30);
    readTx=new TransactionTemplate(reader);readTx.setReadOnly(true);readTx.setTimeout(15);
    readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  Map<String,Object> calculate(UUID project,Map<String,Object> settings,String plan,Instant now) {
    Instant start=now.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant end=start.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
    var usage=insights.summary(project,start,now,now.minus(Duration.ofDays(Billing.retention(plan))));
    BigDecimal budget=Insights.number(settings.get("monthly_budget")),known=Insights.number(usage.get("known_cost"));
    int warning="Pro".equals(plan)?((Number)settings.get("warning_percent")).intValue():80;
    BigDecimal projection=null;
    long elapsed=Duration.between(start,now).toSeconds();
    if(elapsed>=3*86400 && Insights.count(usage,"generations")>=20 && Insights.priced(usage) && Boolean.TRUE.equals(usage.get("retention_complete")))
      projection=known.multiply(BigDecimal.valueOf(Duration.between(start,end).toSeconds())).divide(BigDecimal.valueOf(elapsed),8,RoundingMode.HALF_UP);
    var result=new LinkedHashMap<String,Object>();result.put("period_start",start);result.put("period_end",end);result.put("as_of",now);
    result.put("monthly_budget",budget);result.put("warning_percent",warning);
    result.put("warning_amount",budget.multiply(BigDecimal.valueOf(warning)).divide(BigDecimal.valueOf(100)));
    result.put("known_cost",known);result.put("generations",usage.get("generations"));result.put("price_coverage",usage.get("price_coverage"));
    result.put("retention_complete",usage.get("retention_complete"));result.put("used_percent",known==null?null:Insights.divide(known.multiply(BigDecimal.valueOf(100)),budget));
    result.put("projected_cost",projection);return result;
  }

  Map<String,Object> status(UUID user,UUID project,int page,int size) {
    return readTx.execute(tx->{
      store.project(user,project);var workspace=store.workspace(user);String plan=workspace.get("plan").toString();
      var rows=store.queries().queryForList("SELECT * FROM project_budgets WHERE project_id=?",project);
      Map<String,Object> settings=rows.isEmpty()?new LinkedHashMap<>(Map.of("enabled",false,"monthly_budget",new BigDecimal("100"),"warning_percent",80,"forecast_enabled",true)):rows.get(0);
      if(!"Pro".equals(plan))settings.put("warning_percent",80);
      var result=new LinkedHashMap<String,Object>();result.put("settings",settings);result.put("available",!plan.equals("Free"));result.put("custom_rules",plan.equals("Pro"));
      result.put("configured",!rows.isEmpty());
      result.put("usage",plan.equals("Free")?null:calculate(project,settings,plan,Instant.now()));
      Timestamp cutoff=Timestamp.from(Instant.now().minus(Duration.ofDays(Math.min(90,Billing.retention(plan)))));
      String where=" FROM budget_notifications b JOIN outbox o ON o.id=b.outbox_id WHERE b.project_id=? AND b.created_at>=?";
      result.put("total",store.queries().queryForObject("SELECT count(*)"+where,Long.class,project,cutoff));
      result.put("items",store.queries().queryForList("SELECT b.id,b.kind,b.period_start,b.created_at,b.snapshot->>'known_cost' AS known_cost,b.snapshot->>'monthly_budget' AS monthly_budget,b.snapshot->>'projected_cost' AS projected_cost,CASE WHEN b.cancelled_at IS NOT NULL THEN 'cancelled' WHEN o.sent_at IS NOT NULL THEN 'sent' WHEN o.attempts>=10 THEN 'failed' ELSE 'queued' END AS status"+where+" ORDER BY b.created_at DESC,b.id LIMIT ? OFFSET ?",project,cutoff,size,(long)page*size));
      return result;
    });
  }

  @Scheduled(scheduler="budgetAlertsScheduler",fixedDelay=60000)
  public void schedule() {
    if(!enabled)return;
    var due=store.read.queryForList("SELECT project_id FROM project_budgets WHERE enabled AND next_check<=now() ORDER BY next_check LIMIT 20");
    for(var row:due)try{check((UUID)row.get("project_id"),Instant.now());}catch(RuntimeException e){log.warn("Budget check failed for project {}; will retry",row.get("project_id"));}
  }

  boolean check(UUID project,Instant now) {
    return Boolean.TRUE.equals(writeTx.execute(tx->{
      var rows=store.db.queryForList("SELECT b.*,u.id AS owner,u.email,u.language,u.verified,u.deleted_at AS user_deleted,p.deleted_at AS project_deleted,p.name,w.plan FROM project_budgets b JOIN projects p ON p.id=b.project_id JOIN workspaces w ON w.id=p.workspace_id JOIN users u ON u.id=w.owner_id WHERE b.project_id=? AND b.enabled AND b.next_check<=? FOR UPDATE OF b SKIP LOCKED",project,Timestamp.from(now));
      if(rows.isEmpty())return false;var row=rows.get(0);
      if(row.get("user_deleted")!=null || row.get("project_deleted")!=null || !Boolean.TRUE.equals(row.get("verified")) || "Free".equals(row.get("plan"))) {
        store.db.update("UPDATE project_budgets SET enabled=false WHERE project_id=?",project);return false;
      }
      var snapshot=readTx.execute(s->{var w=insights.paid((UUID)row.get("owner"),project);return calculate(project,row,w.get("plan").toString(),now);});
      store.db.update("UPDATE project_budgets SET next_check=? WHERE project_id=?",Timestamp.from(now.plusSeconds(300)),project);
      BigDecimal cost=Insights.number(snapshot.get("known_cost")),budget=Insights.number(snapshot.get("monthly_budget")),forecast=Insights.number(snapshot.get("projected_cost"));
      String kind=cost!=null && cost.compareTo(budget)>=0?"exceeded":cost!=null && cost.compareTo(Insights.number(snapshot.get("warning_amount")))>=0?"warning":Boolean.TRUE.equals(row.get("forecast_enabled")) && forecast!=null && forecast.compareTo(budget)>0?"forecast":null;
      if(kind==null)return false;
      Timestamp start=Timestamp.from((Instant)snapshot.get("period_start"));
      if(Boolean.TRUE.equals(store.db.queryForObject("SELECT EXISTS(SELECT 1 FROM budget_notifications WHERE project_id=? AND period_start=? AND kind=?)",Boolean.class,project,start,kind)))return false;
      boolean zh="zh-CN".equals(row.get("language"));
      String title=kind.equals("exceeded")?(zh?"模型费用已达到预算":"Model cost reached your budget"):kind.equals("warning")?(zh?"模型费用接近预算":"Model cost is approaching your budget"):(zh?"按当前速度，月末费用可能超预算":"Projected month-end cost may exceed your budget");
      String body=title+"\n"+(zh?"项目：":"Project: ")+row.get("name")+"\nUTC: "+snapshot.get("period_start")+" → "+snapshot.get("period_end")
        +"\n"+(zh?"统计时间：":"As of: ")+now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        +"\n"+(zh?"当时月预算（USD）：":"Budget at generation (USD): ")+WeeklyInsights.value(budget,zh)
        +"\n"+(zh?"已知模型估算费用（USD）：":"Known estimated model cost (USD): ")+WeeklyInsights.value(cost,zh)
        +"\n"+(zh?"价格覆盖率：":"Price coverage: ")+WeeklyInsights.value(snapshot.get("price_coverage")==null?null:Insights.number(snapshot.get("price_coverage")).multiply(BigDecimal.valueOf(100)),zh)+"%"
        +"\n"+(zh?"月末预测（USD）：":"Projected month-end cost (USD): ")+WeeklyInsights.value(forecast,zh)
        +"\n"+(zh?"缺失价格不按零处理。预测按已过去时间线性推算，不能保证未来费用。此提醒不会停止数据接入，也不是 MeterTrail 订阅扣款。":"Missing prices are not zero cost. Projections extrapolate elapsed time and are not guarantees. This reminder does not stop ingestion and is not a MeterTrail subscription charge.")
        +"\n"+publicUrl+"/app/budgets?project="+project;
      Long outbox=store.db.queryForObject("INSERT INTO outbox(recipient,subject,body,budget_project_id) VALUES(?,?,?,?) RETURNING id",Long.class,row.get("email"),"MeterTrail · "+title,store.secrets.seal(body),project);
      try {store.db.update("INSERT INTO budget_notifications(id,project_id,period_start,period_end,kind,snapshot,outbox_id) VALUES(?,?,?,?,?,?::jsonb,?)",UUID.randomUUID(),project,start,Timestamp.from((Instant)snapshot.get("period_end")),kind,json.writeValueAsString(snapshot),outbox);}
      catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
      return true;
    }));
  }

  static boolean mailEligible(Store store,Object outbox) {
    return Boolean.TRUE.equals(store.queries().queryForObject("SELECT EXISTS(SELECT 1 FROM budget_notifications b JOIN project_budgets s ON s.project_id=b.project_id JOIN projects p ON p.id=b.project_id JOIN workspaces w ON w.id=p.workspace_id JOIN users u ON u.id=w.owner_id JOIN outbox o ON o.id=b.outbox_id WHERE b.outbox_id=? AND b.cancelled_at IS NULL AND s.enabled AND p.deleted_at IS NULL AND u.deleted_at IS NULL AND u.verified AND u.email=o.recipient AND w.plan IN ('Core','Pro'))",Boolean.class,outbox));
  }
  @Scheduled(scheduler="budgetAlertsScheduler",fixedDelay=3600000)
  public void cleanup() {
    if(!enabled)return;
    store.db.update("DELETE FROM budget_notifications WHERE id IN (SELECT b.id FROM budget_notifications b JOIN projects p ON p.id=b.project_id JOIN workspaces w ON w.id=p.workspace_id JOIN users u ON u.id=w.owner_id JOIN outbox o ON o.id=b.outbox_id WHERE (b.created_at<now()-interval '90 days' OR p.deleted_at IS NOT NULL OR u.deleted_at IS NOT NULL) AND (o.sent_at IS NOT NULL OR o.attempts>=10) ORDER BY b.created_at LIMIT 500)");
  }
}
