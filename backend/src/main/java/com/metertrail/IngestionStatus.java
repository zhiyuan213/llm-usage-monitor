package com.metertrail;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
@RestController
class IngestionStatus {
 final Store store;final boolean collection;
 IngestionStatus(Store store,@Value("${metertrail.usage-collection-enabled:false}") boolean collection){this.store=store;this.collection=collection;}
 @GetMapping("/api/ingestion-status") Map<String,Object> get(HttpServletRequest req){
  var w=store.workspace(SessionFilter.uid(req));
  long remaining=store.queries().queryForObject("SELECT coalesce(sum(units-consumed_units),0) FROM usage_charges WHERE workspace_id=? AND period_start=? AND kind='addon' AND credited AND NOT revoked AND status='paid'",Long.class,w.get("id"),w.get("period_start"));
  boolean blocked=!store.queries().queryForList("SELECT id FROM usage_charges WHERE workspace_id=? AND kind='overage' AND amount>0 AND status IN ('failed','unknown','processing','submitted') LIMIT 1",w.get("id")).isEmpty();
  return describe(w,remaining,collection,blocked,Instant.now());
 }
 static Map<String,Object> describe(Map<String,Object> w,long remaining,boolean collection,boolean blocked,Instant now){
  String plan=(String)w.get("plan");boolean free=plan.equals("Free"),expired=!((Timestamp)w.get("period_end")).toInstant().isAfter(now);
  long used=((Number)w.get("used_units")).longValue(),overage=((Number)w.get("overage_units")).longValue(),addon=((Number)w.get("addon_consumed")).longValue();
  long available=Math.max(0,Billing.included(plan)-(used-overage-addon))+remaining;
  if(free&&expired)available=Billing.included(plan);
  String reason=free&&expired?"free_period_refresh":"ready";
  if(!free&&expired)reason="period_ended";
  else if(!free&&!"active".equals(w.get("subscription_status")))reason="subscription_inactive";
  else if(available<=0){
   if(!free&&collection&&Boolean.TRUE.equals(w.get("auto_overage"))){
    if(blocked)reason="payment_pending";
    else if(Billing.overage(Math.addExact(100001,overage)).compareTo((BigDecimal)w.get("overage_cap"))>0)reason="overage_cap";
    else reason="overage_ready";
   }else reason="quota_exhausted";
  }
  var result=new LinkedHashMap<String,Object>();result.put("reason",reason);result.put("can_accept_new",Set.of("ready","overage_ready","free_period_refresh").contains(reason));result.put("quota_remaining",available);result.put("subscription_status",w.get("subscription_status"));result.put("period_end",w.get("period_end"));result.put("grace_until",w.get("retention_grace_until"));result.put("as_of",now);return result;
 }
}
