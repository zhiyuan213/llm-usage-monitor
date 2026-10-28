package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientResponseException;

/** Refund submission is claimed durably before contacting the provider; uncertain calls are never retried. */
@RestController
class AdminRefundsController {
  final Store store;
  final CreemPayments creem;
  final TransactionTemplate tx;
  AdminRefundsController(Store store, CreemPayments creem, TransactionTemplate tx) {
    this.store=store;this.creem=creem;this.tx=tx;
  }
  void admin(HttpServletRequest req) {
    if(!Boolean.TRUE.equals(store.db.queryForObject("SELECT is_admin AND verified AND deleted_at IS NULL FROM users WHERE id=?",Boolean.class,SessionFilter.uid(req))))
      throw new ApiError(403,"ADMIN_REQUIRED");
  }
  @GetMapping("/api/admin/payments")
  Map<String,Object> list(@RequestParam(defaultValue="") String search,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,HttpServletRequest req) {
    admin(req);Support.require(page>=0&&page<=100000&&Set.of(5,10,25,50,100).contains(size)&&search.length()<=256,"INVALID_PAGE");
    String where=" FROM payments p JOIN workspaces w ON w.id=p.workspace_id JOIN users u ON u.id=w.owner_id LEFT JOIN admin_refunds r ON r.transaction_id=p.id WHERE (position(lower(?) in lower(u.email))>0 OR position(lower(?) in lower(p.id))>0)";
    return Map.of("items",store.db.queryForList("SELECT p.*,u.email,w.name AS workspace_name,w.payment_provider,r.status AS refund_status,r.cancellation_status,r.reason,r.updated_at AS refund_updated_at"+where+" ORDER BY p.created_at DESC,p.id LIMIT ? OFFSET ?",search,search,size,(long)page*size),
        "total",store.db.queryForObject("SELECT count(*)"+where,Long.class,search,search),"page",page,"size",size);
  }
  Map<String,Object> payment(String id) {
    CreemPayments.requireId(id,"tran_");
    var rows=store.db.queryForList("SELECT p.*,w.payment_provider,w.customer_id,w.subscription_id FROM payments p JOIN workspaces w ON w.id=p.workspace_id WHERE p.id=?",id);
    if(rows.isEmpty())throw new ApiError(404,"PAYMENT_NOT_FOUND");
    var row=rows.get(0);
    if(!"creem".equals(row.get("payment_provider")))throw new ApiError(409,"REFUND_PROVIDER_UNSUPPORTED");
    return row;
  }
  JsonNode transaction(String id,Map<String,Object> payment) {
    JsonNode result=creem.get("/v1/transactions?transaction_id="+id);
    if(result==null||!id.equals(result.path("id").asText()))throw new ApiError(503,"REFUND_PROVIDER_UNAVAILABLE");
    creem.mode(result);
    String expectedMode=creem.url.equals("https://api.creem.io")?"prod":"test";
    if(!expectedMode.equals(result.path("mode").asText()))throw new ApiError(409,"PAYMENT_ENVIRONMENT_MISMATCH");
    if(payment.get("customer_id")==null||!payment.get("customer_id").equals(CreemPayments.id(result.path("customer")))
        ||!Objects.equals(payment.get("subscription_id"),blankNull(CreemPayments.id(result.path("subscription")))))
      throw new ApiError(409,"REFUND_PAYMENT_MISMATCH");
    return result;
  }
  static String blankNull(String value){return value.isBlank()?null:value;}
  static Instant paidAt(JsonNode data) {
    try {
      JsonNode value=data.path("created_at");
      return value.isIntegralNumber()?Instant.ofEpochMilli(value.longValue()):Instant.parse(value.asText());
    } catch(Exception e){throw new ApiError(409,"REFUND_PAYMENT_MISMATCH");}
  }
  Map<String,Object> preview(String id) {
    var p=payment(id);var data=transaction(id,p);Instant paid=paidAt(data),now=Instant.now();
    if(paid.isAfter(now)||now.isAfter(paid.plus(Duration.ofDays(7))))throw new ApiError(409,"REFUND_WINDOW_EXPIRED");
    if(!"completed".equals(p.get("status"))||!"paid".equals(data.path("status").asText())||data.path("refunded_amount").decimalValue().signum()>0
        ||!store.db.queryForList("SELECT id FROM payment_adjustments WHERE transaction_id=? AND action='refund' AND status NOT IN ('rejected','failed','canceled')",id).isEmpty())
      throw new ApiError(409,"PAYMENT_NOT_REFUNDABLE");
    if(!data.hasNonNull("amount_paid")||!data.path("amount_paid").isIntegralNumber()||!data.path("amount_paid").canConvertToLong()||data.path("amount_paid").longValue()<=0)
      throw new ApiError(409,"REFUND_PAYMENT_MISMATCH");
    String currency=data.path("currency").asText();
    BigDecimal amount=PaymentsController.fromMinorUnits(data.path("amount_paid").asText(),currency);
    // Do not cancel a newer renewal while refunding an older payment.
    String sub=CreemPayments.id(data.path("subscription"));boolean cancel=false;
    if(!sub.isBlank()) {
      var subscription=creem.subscription(sub);
      cancel=id.equals(subscription.path("last_transaction_id").asText())&&!"canceled".equals(subscription.path("status").asText());
      if(!id.equals(subscription.path("last_transaction_id").asText()))throw new ApiError(409,"REFUND_NEWER_PAYMENT_EXISTS");
    }
    var result=new LinkedHashMap<String,Object>();result.put("transactionId",id);result.put("amount",amount);result.put("currency",currency);result.put("paidAt",paid.toString());
    result.put("deadline",paid.plus(Duration.ofDays(7)).toString());result.put("mode",data.path("mode").asText());result.put("cancelSubscription",cancel);
    result.put("subscriptionId",sub);result.put("workspaceId",p.get("workspace_id"));return result;
  }
  @GetMapping("/api/admin/payments/{id}/refund-preview")
  Map<String,Object> preview(@PathVariable String id,HttpServletRequest req){admin(req);return preview(id);}

  @PostMapping("/api/admin/payments/{id}/refund")
  Map<String,Object> refund(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest req) {
    admin(req);payment(id);
    Support.require(id.equals(body.path("confirmation").asText()),"REFUND_CONFIRMATION_REQUIRED");
    String reason=Support.text(body,"reason",1000).strip();Support.require(!reason.isBlank(),"REFUND_REASON_REQUIRED");
    var existing=store.db.queryForList("SELECT * FROM admin_refunds WHERE transaction_id=?",id);
    if(!existing.isEmpty())return existing.get(0);
    var quote=preview(id);
    BigDecimal expected;
    try{expected=new BigDecimal(body.path("amount").asText());}catch(Exception e){throw new ApiError(409,"REFUND_QUOTE_CHANGED");}
    if(expected.compareTo((BigDecimal)quote.get("amount"))!=0||!quote.get("currency").equals(body.path("currency").asText())
        ||!Objects.equals(quote.get("cancelSubscription"),body.path("cancelSubscription").asBoolean()))throw new ApiError(409,"REFUND_QUOTE_CHANGED");
    Boolean claimed=tx.execute(s->{
      int inserted=store.db.update("INSERT INTO admin_refunds(transaction_id,workspace_id,actor_id,reason,amount,currency,paid_at,provider_mode,subscription_id,status,cancellation_status) VALUES(?,?,?,?,?,?,?,?,?,'submitting',?) ON CONFLICT DO NOTHING",id,quote.get("workspaceId"),SessionFilter.uid(req),reason,quote.get("amount"),quote.get("currency"),Timestamp.from(Instant.parse((String)quote.get("paidAt"))),quote.get("mode"),blankNull((String)quote.get("subscriptionId")),Boolean.TRUE.equals(quote.get("cancelSubscription"))?"pending":"not_required");
      if(inserted==1)store.audit(SessionFilter.uid(req),(UUID)quote.get("workspaceId"),"payment.refund.request",id,true,req);
      return inserted==1;
    });
    if(Boolean.TRUE.equals(claimed)) {
      try {
        JsonNode result=creem.post("/v1/refunds",Map.of("transaction_id",id));
        String status=result==null?"unknown":result.path("status").asText();
        if(!Set.of("pending","requiresAction","succeeded","failed","canceled").contains(status))status="unknown";
        update(id,status);
      } catch(RestClientResponseException e) {
        update(id,e.getStatusCode().is4xxClientError()&&!Set.of(408,409,429).contains(e.getStatusCode().value())?"rejected":"unknown");
      } catch(Exception e){update(id,"unknown");}
    }
    return reconcile(id);
  }
  void update(String id,String status){store.db.update("UPDATE admin_refunds SET status=?,updated_at=now() WHERE transaction_id=? AND status<>'succeeded'",status,id);}
  @PostMapping("/api/admin/payments/{id}/refund-status")
  Map<String,Object> refresh(@PathVariable String id,HttpServletRequest req){admin(req);payment(id);return reconcile(id);}
  Map<String,Object> reconcile(String id) {
    var rows=store.db.queryForList("SELECT * FROM admin_refunds WHERE transaction_id=?",id);
    if(rows.isEmpty())throw new ApiError(404,"REFUND_NOT_FOUND");
    var row=rows.get(0);
    try {
      var data=transaction(id,payment(id));
      if("refunded".equals(data.path("status").asText()))update(id,"succeeded");
      row=store.db.queryForMap("SELECT * FROM admin_refunds WHERE transaction_id=?",id);
      if("succeeded".equals(row.get("status"))&&Set.of("pending","unknown").contains(row.get("cancellation_status"))) {
        String sub=(String)row.get("subscription_id");var subscription=creem.subscription(sub);
        if(!id.equals(subscription.path("last_transaction_id").asText()))throw new ApiError(409,"REFUND_NEWER_PAYMENT_EXISTS");
        if(!"canceled".equals(subscription.path("status").asText())) {
          // A refund and cancellation are separate provider operations.
          subscription=creem.post("/v1/subscriptions/"+sub+"/cancel",Map.of("mode","immediate"));
        }
        if(subscription==null||!sub.equals(subscription.path("id").asText())||!"canceled".equals(subscription.path("status").asText()))throw new ApiError(503,"REFUND_PROVIDER_UNAVAILABLE");
        creem.mode(subscription);
        tx.executeWithoutResult(s->{
          var current=store.db.queryForList("SELECT * FROM workspaces WHERE id=? AND subscription_id=? AND payment_provider='creem' FOR UPDATE",rowWorkspace(id),sub);
          if(!current.isEmpty())SubscriptionRetention.endPaidAccess(store,current.get(0),Instant.now());
          store.db.update("UPDATE workspaces SET subscription_status='canceled',auto_overage=false,scheduled_change=NULL,retention_grace_until=coalesce(retention_grace_until,least(period_end,now())+interval '30 days'),payment_updated_at=greatest(coalesce(payment_updated_at,now()),now()) WHERE id=? AND subscription_id=? AND payment_provider='creem'",rowWorkspace(id),sub);
          store.db.update("UPDATE admin_refunds SET cancellation_status='completed',updated_at=now() WHERE transaction_id=?",id);
        });
      }
    } catch(Exception e) {
      store.db.update("UPDATE admin_refunds SET cancellation_status='unknown',updated_at=now() WHERE transaction_id=? AND status='succeeded' AND cancellation_status='pending'",id);
    }
    return store.db.queryForMap("SELECT * FROM admin_refunds WHERE transaction_id=?",id);
  }
  UUID rowWorkspace(String id){return store.db.queryForObject("SELECT workspace_id FROM admin_refunds WHERE transaction_id=?",UUID.class,id);}
}
