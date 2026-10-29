package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class OperationalInsightsController {
  final Store store;final Insights insights;final ReliabilityInsights reliability;final BudgetAlerts budgets;final RateLimits limits;
  OperationalInsightsController(Store store,Insights insights,ReliabilityInsights reliability,BudgetAlerts budgets,RateLimits limits) {
    this.store=store;this.insights=insights;this.reliability=reliability;this.budgets=budgets;this.limits=limits;
  }
  @GetMapping("/api/projects/{project}/insights/reliability")
  Map<String,Object> reliability(@PathVariable UUID project,@RequestParam(defaultValue="7") int days,
      @RequestParam(defaultValue="trace") String scope,@RequestParam(defaultValue="feature") String dimension,
      @RequestParam(defaultValue="errors") String view,@RequestParam(defaultValue="5000") long slowMs,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="5") int size,HttpServletRequest req) {
    limits.check("operational-read:"+SessionFilter.uid(req),30);
    return reliability.analyze(SessionFilter.uid(req),project,days,scope,dimension,view,slowMs,page,size);
  }
  @GetMapping("/api/projects/{project}/insights/budget")
  Map<String,Object> budget(@PathVariable UUID project,@RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="5") int size,HttpServletRequest req) {
    InsightsController.pagination(page,size);limits.check("operational-read:"+SessionFilter.uid(req),30);
    return budgets.status(SessionFilter.uid(req),project,page,size);
  }
  @PostMapping("/api/projects/{project}/insights/budget")
  @Transactional
  Map<String,Boolean> save(@PathVariable UUID project,@RequestBody JsonNode body,HttpServletRequest req) {
    UUID user=SessionFilter.uid(req);var p=store.project(user,project);var workspace=store.workspace(user);
    limits.check("budget-save:"+user,10);
    Support.require(body.path("enabled").isBoolean() && body.path("forecastEnabled").isBoolean()
      && body.path("warningPercent").isIntegralNumber() && body.path("warningPercent").canConvertToInt(),"INVALID_BUDGET_SETTINGS");
    boolean enabled=body.path("enabled").asBoolean();
    if(enabled)insights.paid(user,project);
    Support.require(!enabled || Boolean.TRUE.equals(SessionFilter.user(req).get("verified")),"EMAIL_NOT_VERIFIED");
    int warning=body.path("warningPercent").asInt();
    Support.require(warning>=50 && warning<=95,"INVALID_BUDGET_SETTINGS");
    if(enabled && !"Pro".equals(workspace.get("plan")) && warning!=80)throw new ApiError(403,"PRO_CUSTOM_RULES_REQUIRED");
    BigDecimal amount;
    Support.require(body.path("monthlyBudget").asText().length()<=32,"INVALID_BUDGET_SETTINGS");
    try {amount=new BigDecimal(body.path("monthlyBudget").asText());}catch(Exception e){throw new ApiError(400,"INVALID_BUDGET_SETTINGS");}
    Support.require(amount.signum()>0 && amount.compareTo(new BigDecimal("1000000000"))<=0 && amount.scale()<=6,"INVALID_BUDGET_SETTINGS");
    store.db.update("INSERT INTO project_budgets(project_id,monthly_budget,warning_percent,enabled,forecast_enabled) VALUES(?,?,?,?,?) ON CONFLICT(project_id) DO UPDATE SET monthly_budget=excluded.monthly_budget,warning_percent=excluded.warning_percent,enabled=excluded.enabled,forecast_enabled=excluded.forecast_enabled,next_check=now(),updated_at=now()",project,amount,warning,enabled,body.path("forecastEnabled").asBoolean());
    if(!enabled) {
      store.db.update("UPDATE outbox SET attempts=10,body='' WHERE sent_at IS NULL AND id IN (SELECT outbox_id FROM budget_notifications WHERE project_id=?)",project);
      store.db.update("UPDATE budget_notifications b SET cancelled_at=now() FROM outbox o WHERE b.project_id=? AND b.outbox_id=o.id AND o.sent_at IS NULL",project);
    }
    store.audit(user,(UUID)p.get("workspace_id"),"budget.save",project.toString(),true,req);
    return Map.of("enabled",enabled);
  }
}
