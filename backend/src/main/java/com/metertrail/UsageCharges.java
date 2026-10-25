package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
class UsageCharges {
  final Store store;
  final PaymentsController payments;
  final TransactionTemplate tx;
  final boolean enabled, worker;
  final String publicUrl;
  UsageCharges(Store store, PaymentsController payments, TransactionTemplate tx,
      @Value("${metertrail.usage-collection-enabled:false}") boolean enabled,
      @Value("${metertrail.worker-enabled}") boolean worker,
      @Value("${metertrail.public-url}") String publicUrl) {
    this.store=store; this.payments=payments; this.tx=tx; this.enabled=enabled; this.worker=worker; this.publicUrl=publicUrl;
  }
  @GetMapping("/api/billing/usage-charges")
  Map<String,Object> list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="5") int size, HttpServletRequest req) {
    Support.require(page>=0 && size>=1 && size<=100,"INVALID_PAGE");
    var w=store.workspace(SessionFilter.uid(req));
    long used=((Number)w.get("used_units")).longValue(), overage=((Number)w.get("overage_units")).longValue(), consumed=((Number)w.get("addon_consumed")).longValue();
    Long remaining=store.queries().queryForObject("SELECT coalesce(sum(units-consumed_units),0) FROM usage_charges WHERE workspace_id=? AND period_start=? AND kind='addon' AND credited AND NOT revoked AND status='paid'",Long.class,w.get("id"),w.get("period_start"));
    long available=Math.max(0,Billing.included((String)w.get("plan"))-(used-overage-consumed))+remaining;
    if(!"Free".equals(w.get("plan")) && (!"active".equals(w.get("subscription_status")) || !((Timestamp)w.get("period_end")).toInstant().isAfter(Instant.now())))available=0;
    return Map.of("availableUnits",available,"enabled",enabled,"packUnits",100000,"packAmount","8.00","currency","USD","total",store.queries().queryForObject("SELECT count(*) FROM usage_charges WHERE workspace_id=?",Long.class,w.get("id")),"charges",store.queries().queryForList("SELECT id,kind,units,amount,currency,status,transaction_id,credited,revoked,period_start,period_end,created_at,checkout_url FROM usage_charges WHERE workspace_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",w.get("id"),size,(long)page*size));
  }
  static void active(Map<String,Object> w) {
    if("Free".equals(w.get("plan")) || !"active".equals(w.get("subscription_status")) || w.get("subscription_id")==null || !((Timestamp)w.get("period_end")).toInstant().isAfter(Instant.now().plusSeconds(900)))
      throw new ApiError(409,"ADDON_UNAVAILABLE");
  }
  static Map<String,Object> item(UUID id,String product,BigDecimal amount,String name) {
    var price=new HashMap<String,Object>();
    price.put("product_id",product); price.put("description",ChargeLedger.marker(id)); price.put("name",name);
    price.put("billing_cycle",null); price.put("tax_mode","internal");
    price.put("quantity",Map.of("minimum",1,"maximum",1));
    price.put("unit_price",Map.of("amount",amount.movePointRight(2).toBigIntegerExact().toString(),"currency_code","USD"));
    return Map.of("quantity",1,"price",price);
  }
  @PostMapping("/api/billing/addons")
  Map<String,Object> purchase(@RequestBody JsonNode b,HttpServletRequest req) {
    if(!enabled) throw new ApiError(503,"USAGE_COLLECTION_NOT_CONFIGURED");
    Support.require(b.path("consent").asBoolean(),"BILLING_CONSENT_REQUIRED");
    UUID workspace=(UUID)store.workspace(SessionFilter.uid(req)).get("id");
    var charge=tx.execute(status->{
      var w=store.queries().queryForMap("SELECT * FROM workspaces WHERE id=? FOR UPDATE",workspace); active(w);
      var existing=store.queries().queryForList("SELECT * FROM usage_charges WHERE workspace_id=? AND kind='addon' AND status IN ('ready','processing','submitted','unknown')",workspace);
      if(!existing.isEmpty()) return existing.get(0);
      UUID id=UUID.randomUUID();
      store.db.update("INSERT INTO usage_charges(id,workspace_id,subscription_id,period_start,period_end,kind,units,amount) VALUES(?,?,?,?,?,'addon',100000,8)",id,workspace,w.get("subscription_id"),w.get("period_start"),w.get("period_end"));
      store.audit(SessionFilter.uid(req),workspace,"billing.addon",id.toString(),true,req);
      return store.queries().queryForMap("SELECT * FROM usage_charges WHERE id=?",id);
    });
    if(charge.get("checkout_url")!=null) return Map.of("id",charge.get("id"),"url",charge.get("checkout_url"));
    submit(charge);
    var result=store.queries().queryForMap("SELECT status,checkout_url FROM usage_charges WHERE id=?",charge.get("id"));
    if(result.get("checkout_url")==null) throw new ApiError(409,"CHARGE_PENDING_REVIEW");
    return Map.of("id",charge.get("id"),"url",result.get("checkout_url"));
  }
  void submit(Map<String,Object> c) {
    // Commit the claim BEFORE the external write. Unknown results are never automatically retried.
    int claimed=store.db.update("UPDATE usage_charges SET status='processing' WHERE id=? AND status='ready'",c.get("id"));
    if(claimed!=1) return;
    try {
      JsonNode sub=payments.readSubscription((String)c.get("subscription_id")).path("data");
      boolean active="active".equals(sub.path("status").asText());
      if("addon".equals(c.get("kind")) && !active) throw new ApiError(409,"SUBSCRIPTION_NOT_ACTIVE");
      String product=sub.path("items").path(0).path("price").path("product_id").asText();
      if(product.isBlank()) throw new ApiError(503,"USAGE_PRODUCT_UNAVAILABLE");
      UUID id=(UUID)c.get("id");
      var item=item(id,product,(BigDecimal)c.get("amount"),"addon".equals(c.get("kind"))?"100,000 units — current period":"MeterTrail period overage");
      JsonNode response;
      if("addon".equals(c.get("kind")) || !active) {
        response=payments.postUsage("/transactions",Map.of("items",List.of(item),"customer_id",sub.path("customer_id").asText(),"collection_mode","automatic","checkout",Map.of("url",publicUrl+"/pay"),"custom_data",Map.of("metertrail_charge",id)));
        JsonNode data=response.path("data");
        if(!data.path("checkout").hasNonNull("url")) throw new IllegalStateException("Missing checkout");
        store.db.update("UPDATE usage_charges SET transaction_id=?,checkout_url=?,status=CASE WHEN status='processing' THEN 'submitted' ELSE status END WHERE id=?",data.path("id").asText(),data.path("checkout").path("url").asText(),id);
      } else {
        payments.postUsage("/subscriptions/"+c.get("subscription_id")+"/charge",Map.of("effective_from","immediately","items",List.of(item)));
        store.db.update("UPDATE usage_charges SET status='submitted' WHERE id=? AND status='processing'",id);
      }
    } catch(Exception e) {
      // GET/config errors are safe failures; POST errors are conservatively unknown (including 5xx).
      store.db.update("UPDATE usage_charges SET status='unknown' WHERE id=? AND status='processing'",c.get("id"));
    }
  }
  void reconcile(Map<String,Object> c) {
    if(c.get("transaction_id")!=null) {
      applyReconciled(payments.readUsageTransaction((String)c.get("transaction_id")).path("data"));
      return;
    }
    JsonNode sub=payments.readSubscription((String)c.get("subscription_id")).path("data");
    String customer=sub.path("customer_id").asText();
    String after="";
    for(int page=0;page<5;page++) {
      JsonNode response=payments.usageTransactions(customer,after,((Timestamp)c.get("created_at")).toInstant());
      JsonNode rows=response.path("data");
      for(JsonNode data:rows) if(c.get("id").equals(ChargeLedger.reference(data))) {
        applyReconciled(data);
        return;
      }
      if(rows.size()<30) return;
      after=rows.path(rows.size()-1).path("id").asText();
    }
  }

  void applyReconciled(JsonNode data) {
    JsonNode event=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("event_id","usage_reconcile_"+Support.hash(data.path("id").asText()+data.path("updated_at").asText()),"event_type","transaction.updated","occurred_at",data.path("updated_at").asText(),"data",data));
    tx.executeWithoutResult(status->payments.applyEvent(event));
  }

  @Scheduled(initialDelay=60000,fixedDelay=60000,scheduler="usageBillingScheduler")
  void collect() {
    if(!enabled || !worker) return;
    // Cover expired periods even if the provider's renewal/cancellation webhook is delayed.
    for(var row:store.queries().queryForList("SELECT id FROM workspaces WHERE plan<>'Free' AND subscription_id IS NOT NULL AND period_end<=now()"))
      tx.executeWithoutResult(status->{ var w=store.queries().queryForMap("SELECT * FROM workspaces WHERE id=? FOR UPDATE",row.get("id")); ChargeLedger.close(store,w); });
    for(var c:store.queries().queryForList("SELECT * FROM usage_charges WHERE kind='overage' AND status='ready' ORDER BY created_at LIMIT 50")) submit(c);
    store.db.update("UPDATE usage_charges SET status='unknown' WHERE status='processing' AND created_at<now()-interval '5 minutes'");
    for(var c:store.queries().queryForList("SELECT * FROM usage_charges WHERE status IN ('submitted','unknown','failed') ORDER BY checked_at LIMIT 5")) {
      try { reconcile(c); } catch(Exception e) { /* Remain unresolved; never infer success from missing evidence. */ }
      finally { store.db.update("UPDATE usage_charges SET checked_at=now() WHERE id=?",c.get("id")); }
    }
  }
}
