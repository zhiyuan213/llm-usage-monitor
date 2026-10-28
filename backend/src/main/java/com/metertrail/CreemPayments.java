package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

/** Creem uses separate intents, provider scope and receipt IDs from legacy billing. */
@RestController
class CreemPayments {
  final Store store;
  final TransactionTemplate tx;
  final RestClient client;
  final String key, secret, core, pro, url, publicUrl;
  final boolean checkoutEnabled;

  CreemPayments(Store store, TransactionTemplate tx,
      @Value("${metertrail.creem-api-key:}") String key,
      @Value("${metertrail.creem-secret:}") String secret,
      @Value("${metertrail.creem-core-product:}") String core,
      @Value("${metertrail.creem-pro-product:}") String pro,
      @Value("${metertrail.creem-url:https://api.creem.io}") String url,
      @Value("${metertrail.public-url}") String publicUrl,
      @Value("${metertrail.creem-checkout-enabled:false}") boolean checkoutEnabled) {
    this.store=store;this.tx=tx;this.key=key;this.secret=secret;this.core=core;this.pro=pro;
    this.url=url.replaceAll("/+$","");this.publicUrl=publicUrl.replaceAll("/+$","");
    this.checkoutEnabled=checkoutEnabled;
    var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(
        java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    client=RestClient.builder().requestFactory(factory).defaultHeader("User-Agent","MeterTrail-billing/1.0").build();
  }

  boolean enabled() { return checkoutEnabled; }
  boolean owns(Map<String,Object> w) { return "creem".equals(w.get("payment_provider")); }
  String product(String plan) {
    if(!Set.of("Core","Pro").contains(plan))throw new ApiError(400,"INVALID_PLAN");
    return plan.equals("Core")?core:pro;
  }
  void configured() {
    if(key.isBlank() || secret.isBlank() || core.isBlank() || pro.isBlank()
        || !Set.of("https://api.creem.io","https://test-api.creem.io").contains(url))
      throw new ApiError(503,"PAYMENTS_NOT_CONFIGURED");
  }
  JsonNode get(String path) {
    configured();
    return client.get().uri(url+path).header("x-api-key",key).retrieve().body(JsonNode.class);
  }
  JsonNode post(String path,Object body) {
    configured();
    return client.post().uri(url+path).header("x-api-key",key).body(body).retrieve().body(JsonNode.class);
  }
  static String id(JsonNode value) { return value.isTextual()?value.asText():value.path("id").asText(); }
  static void requireId(String id,String prefix) {
    Support.require(id.matches(prefix+"[A-Za-z0-9]{1,120}"),"INVALID_PAYMENT_ID");
  }
  void mode(JsonNode object) {
    String mode=object.path("mode").asText();
    if(!mode.isBlank() && !(url.equals("https://api.creem.io")?mode.equals("prod"):Set.of("test","sandbox").contains(mode)))
      throw new ApiError(400,"PAYMENT_ENVIRONMENT_MISMATCH");
  }
  JsonNode subscription(String id) {
    requireId(id,"sub_");
    JsonNode result=get("/v1/subscriptions?subscription_id="+id);
    if(result==null || !id.equals(result.path("id").asText()))throw new ApiError(503,"SUBSCRIPTION_UNAVAILABLE");
    mode(result);return result;
  }
  String checkedUrl(String raw) {
    URI target;
    try { target=URI.create(raw); }catch(Exception e){throw new ApiError(503,"CHECKOUT_UNAVAILABLE");}
    if(!"https".equals(target.getScheme()) || target.getUserInfo()!=null || target.getPort()!=-1
        || !Set.of("creem.io","www.creem.io","checkout.creem.io").contains(target.getHost()))
      throw new ApiError(503,"CHECKOUT_UNAVAILABLE");
    return raw;
  }
  Map<String,String> checkout(String plan,HttpServletRequest req) {
    configured();
    if(!enabled())throw new ApiError(503,"PAYMENTS_NOT_CONFIGURED");
    String product=product(plan);
    var w=store.workspace(SessionFilter.uid(req));
    if(w.get("subscription_id")!=null)throw new ApiError(409,"USE_BILLING_PORTAL");
    UUID intent=UUID.randomUUID();
    store.db.update("INSERT INTO checkout_intents(id,workspace_id,plan,payment_provider) VALUES(?,?,?,'creem')",intent,w.get("id"),plan);
    JsonNode result=post("/v1/checkouts",Map.of("product_id",product,"request_id",intent.toString(),
        "success_url",publicUrl+"/app/billing","metadata",Map.of("metertrail_intent",intent.toString())));
    if(result==null)throw new ApiError(503,"CHECKOUT_UNAVAILABLE");
    requireId(result.path("id").asText(),"ch_");mode(result);
    String target=checkedUrl(result.path("checkout_url").asText());
    store.db.update("UPDATE checkout_intents SET transaction_id=? WHERE id=? AND (transaction_id IS NULL OR transaction_id=?)",
        result.path("id").asText(),intent,result.path("id").asText());
    return Map.of("url",target);
  }
  Map<String,String> portal(Map<String,Object> w) {
    if(!owns(w) || w.get("customer_id")==null)throw new ApiError(503,"PAYMENTS_NOT_CONFIGURED");
    JsonNode result=post("/v1/customers/billing",Map.of("customer_id",w.get("customer_id")));
    if(result==null)throw new ApiError(503,"PORTAL_UNAVAILABLE");
    return Map.of("url",checkedUrl(result.path("customer_portal_link").asText()));
  }

  static boolean signature(String secret,String header,String body) {
    return !secret.isBlank() && header!=null && header.matches("[a-fA-F0-9]{64}")
        && Support.equal(Support.hmac(secret,body),header.toLowerCase(Locale.ROOT));
  }
  @PostMapping("/webhooks/creem")
  Map<String,String> webhook(@RequestHeader(value="creem-signature",required=false) String header,@RequestBody String body) {
    if(secret.isBlank())throw new ApiError(503,"PAYMENTS_NOT_CONFIGURED");
    if(!signature(secret,header,body))throw new ApiError(401,"INVALID_SIGNATURE");
    JsonNode event=Support.parse(body);
    String eventId=Support.text(event,"id",128),type=Support.text(event,"eventType",128);
    if(!event.path("created_at").canConvertToLong())throw new ApiError(400,"INVALID_PAYMENT_DATE");
    Instant at=Instant.ofEpochMilli(event.path("created_at").asLong());
    if(at.isAfter(Instant.now().plusSeconds(300)))throw new ApiError(400,"INVALID_PAYMENT_DATE");
    JsonNode object=event.path("object");mode(object);
    var seen=store.queries().queryForList("SELECT body_hash FROM creem_webhook_receipts WHERE event_id=?",eventId);
    if(!seen.isEmpty())return receipt(eventId,type,body,at,"duplicate");
    // A sparse checkout payload needs the provider's authoritative period; never guess it.
    JsonNode subscription=object;
    boolean paid=type.equals("subscription.paid");
    if(type.equals("checkout.completed")) {
      if(!"completed".equals(object.path("status").asText()) || !"paid".equals(object.path("order").path("status").asText()))
        throw new ApiError(400,"PAYMENT_NOT_COMPLETED");
      String sub=id(object.path("subscription"));
      if(sub.isBlank())return receipt(eventId,type,body,at,"ignored");
      if(object.path("metadata").path("metertrail_intent").asText().isBlank()
          && store.queries().queryForList("SELECT id FROM workspaces WHERE payment_provider='creem' AND subscription_id=?",sub).isEmpty())
        return receipt(eventId,type,body,at,"ignored");
      subscription=subscription(sub);paid=true;
    }
    JsonNode selected=subscription;boolean paidEvent=paid;
    return tx.execute(status->apply(eventId,type,Support.hash(body),at,object,selected,paidEvent));
  }
  Map<String,String> receipt(String id,String type,String body,Instant at,String outcome) {
    return tx.execute(status->finish(id,type,Support.hash(body),at,outcome));
  }
  Map<String,String> finish(String id,String type,String hash,Instant at,String outcome) {
    int inserted=store.db.update("INSERT INTO creem_webhook_receipts(event_id,event_type,body_hash,occurred_at,outcome) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
        id,type,hash,Timestamp.from(at),outcome);
    if(inserted==0) {
      var previous=store.queries().queryForMap("SELECT body_hash FROM creem_webhook_receipts WHERE event_id=?",id);
      if(!hash.equals(previous.get("body_hash")))throw new ApiError(409,"WEBHOOK_EVENT_CONFLICT");
      return Map.of("status","duplicate");
    }
    return Map.of("status",outcome);
  }
  Map<String,String> apply(String eventId,String type,String hash,Instant at,JsonNode object,JsonNode sub,boolean paid) {
    var previous=store.queries().queryForList("SELECT body_hash FROM creem_webhook_receipts WHERE event_id=?",eventId);
    if(!previous.isEmpty())return finish(eventId,type,hash,at,"duplicate");
    boolean refund=type.equals("refund.created"),dispute=type.equals("dispute.created");
    boolean subscriptionEvent=Set.of("subscription.paid","subscription.active","subscription.update","subscription.scheduled_cancel",
        "subscription.canceled","subscription.past_due","subscription.unpaid","subscription.expired","subscription.paused","subscription.trialing").contains(type);
    if(!subscriptionEvent && !type.equals("checkout.completed") && !refund && !dispute)
      return finish(eventId,type,hash,at,"ignored");
    String subscriptionId=(refund||dispute)?id(object.path("subscription")):id(sub);
    if(subscriptionId.isBlank())subscriptionId=id(object.path("transaction").path("subscription"));
    requireId(subscriptionId,"sub_");
    var rows=store.queries().queryForList("SELECT * FROM workspaces WHERE payment_provider='creem' AND subscription_id=? FOR UPDATE",subscriptionId);
    UUID intent=null;
    String metadata=object.path("metadata").path("metertrail_intent").asText(sub.path("metadata").path("metertrail_intent").asText());
    if(!metadata.isBlank()) {
      intent=Support.uuid(metadata);
      var intents=store.queries().queryForList("SELECT c.*,w.subscription_id,w.payment_provider AS workspace_provider FROM checkout_intents c JOIN workspaces w ON w.id=c.workspace_id WHERE c.id=? AND c.payment_provider='creem' FOR UPDATE OF c,w",intent);
      if(intents.isEmpty())return finish(eventId,type,hash,at,"ignored");
      var c=intents.get(0);
      if(c.get("subscription_id")!=null && (!subscriptionId.equals(c.get("subscription_id")) || !"creem".equals(c.get("workspace_provider"))))
        throw new ApiError(409,"SUBSCRIPTION_CONFLICT");
      if(rows.isEmpty() && !product(c.get("plan").toString()).equals(id(sub.path("product"))))throw new ApiError(400,"UNKNOWN_PRICE");
      if(type.equals("checkout.completed")) {
        String checkout=id(object);requireId(checkout,"ch_");
        if(c.get("transaction_id")!=null && !checkout.equals(c.get("transaction_id")))throw new ApiError(409,"CHECKOUT_CONFLICT");
        store.db.update("UPDATE checkout_intents SET transaction_id=?,confirmed=true WHERE id=?",checkout,intent);
      }
      if(rows.isEmpty())rows=store.queries().queryForList("SELECT * FROM workspaces WHERE id=? FOR UPDATE",c.get("workspace_id"));
    }
    if(rows.isEmpty())return finish(eventId,type,hash,at,"ignored");
    // Recheck after acquiring the workspace lock: another delivery may have committed while we waited.
    if(!store.queries().queryForList("SELECT event_id FROM creem_webhook_receipts WHERE event_id=?",eventId).isEmpty())
      return finish(eventId,type,hash,at,"duplicate");
    var w=rows.get(0);UUID workspace=(UUID)w.get("id");
    if(!paid && !refund && !dispute && !owns(w))return finish(eventId,type,hash,at,"awaiting_payment");
    if(refund || dispute) {
      JsonNode transaction=object.path("transaction");String transactionId=id(transaction);
      if(transactionId.isBlank())throw new ApiError(400,"INVALID_PAYMENT_ID");
      String currency=object.path("refund_currency").asText(object.path("currency").asText("USD"));
      String amount=object.path(refund?"refund_amount":"amount").asText();
      store.db.update("INSERT INTO payment_adjustments(id,workspace_id,transaction_id,action,status,amount,currency,updated_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,amount=excluded.amount,updated_at=excluded.updated_at WHERE payment_adjustments.updated_at<excluded.updated_at",
          id(object),workspace,transactionId,refund?"refund":"dispute",refund?("succeeded".equals(object.path("status").asText())?"approved":object.path("status").asText()):"pending_approval",
          PaymentsController.fromMinorUnits(amount,currency),currency,Timestamp.from(at));
      if(refund && "succeeded".equals(object.path("status").asText()))
        store.db.update("UPDATE admin_refunds r SET status='succeeded',updated_at=now() WHERE transaction_id=? AND workspace_id=? AND amount<=(SELECT coalesce(sum(a.amount),0) FROM payment_adjustments a WHERE a.transaction_id=r.transaction_id AND a.workspace_id=r.workspace_id AND a.action='refund' AND a.status='approved' AND a.currency=r.currency)",transactionId,workspace);
      return finish(eventId,type,hash,at,"processed");
    }
    if(w.get("payment_updated_at")!=null && at.isBefore(((Timestamp)w.get("payment_updated_at")).toInstant()))
      return finish(eventId,type,hash,at,"stale");
    String product=id(sub.path("product")),plan=product.equals(core)&&!core.isBlank()?"Core":product.equals(pro)&&!pro.isBlank()?"Pro":null;
    if(plan==null)throw new ApiError(400,"UNKNOWN_PRICE");
    String providerStatus=sub.path("status").asText();
    if(paid) {
      if(!Set.of("active","scheduled_cancel").contains(providerStatus))throw new ApiError(409,"PAYMENT_STATE_CONFLICT");
      Instant start=parseTime(sub,"current_period_start_date"),end=parseTime(sub,"current_period_end_date");
      if(!end.isAfter(start))throw new ApiError(400,"INVALID_BILLING_PERIOD");
      Instant previousStart=((Timestamp)w.get("period_start")).toInstant();
      if(start.isBefore(previousStart) && !"Free".equals(w.get("plan")))return finish(eventId,type,hash,at,"stale");
      boolean reset="Free".equals(w.get("plan"))||start.isAfter(previousStart);
      store.db.update("UPDATE workspaces SET payment_provider='creem',plan=?,subscription_id=?,customer_id=?,subscription_status='active',period_start=?,period_end=?,retention_grace_until=NULL,auto_overage=false,used_units=CASE WHEN ? THEN 0 ELSE used_units END,addon_units=CASE WHEN ? THEN 0 ELSE addon_units END,overage_units=CASE WHEN ? THEN 0 ELSE overage_units END,addon_consumed=CASE WHEN ? THEN 0 ELSE addon_consumed END,payment_updated_at=? WHERE id=?",
          plan,subscriptionId,id(sub.path("customer")),Timestamp.from(start),Timestamp.from(end),reset,reset,reset,reset,Timestamp.from(at),workspace);
      JsonNode order=type.equals("checkout.completed")?object.path("order"):sub.path("last_transaction");
      String transactionId=sub.path("last_transaction_id").asText(id(order.path("transaction")));
      if(!transactionId.isBlank()) {
        String currency=order.path("currency").asText("USD");
        store.db.update("INSERT INTO payments(id,workspace_id,status,amount,currency,updated_at) VALUES(?,?,'completed',?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,amount=excluded.amount,updated_at=excluded.updated_at WHERE payments.updated_at<excluded.updated_at",
            transactionId,workspace,order.hasNonNull("amount")?PaymentsController.fromMinorUnits(order.path("amount_paid").asText(order.path("amount").asText()),currency):null,currency,Timestamp.from(at));
      }
    } else if(Set.of("canceled","past_due","unpaid","expired","paused","trialing").contains(providerStatus)) {
      store.db.update("UPDATE workspaces SET subscription_status=?,auto_overage=false,retention_grace_until=coalesce(retention_grace_until,least(period_end,?::timestamptz)+interval '30 days'),payment_updated_at=? WHERE id=?",
          providerStatus,Timestamp.from(at),Timestamp.from(at),workspace);
    } else if(!"active".equals(w.get("subscription_status"))) {
      // active/update events cannot grant access without an authoritative paid event.
      return finish(eventId,type,hash,at,"awaiting_payment");
    }
    String scheduled=providerStatus.equals("scheduled_cancel")?"{\"action\":\"cancel\",\"effective_at\":\""+sub.path("current_period_end_date").asText()+"\"}":"null";
    store.db.update("UPDATE workspaces SET scheduled_change=?::jsonb WHERE id=?",scheduled,workspace);
    store.audit(null,workspace,"payment.creem."+type,eventId,true,null);
    return finish(eventId,type,hash,at,"processed");
  }
  static Instant parseTime(JsonNode object,String field) {
    try{return Instant.parse(object.path(field).asText()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}catch(Exception e){throw new ApiError(400,"INVALID_BILLING_PERIOD");}
  }
}
