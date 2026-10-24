package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** All mutations run in the workspace-locked webhook or settlement transaction. */
final class ChargeLedger {
  static BigDecimal accrued(Map<String,Object> w) {
    return Billing.overage(Math.addExact(100000,((Number)w.getOrDefault("overage_units",0L)).longValue()));
  }
  static long included(Map<String,Object> w) {
    return Math.addExact(Billing.included((String)w.get("plan")),((Number)w.getOrDefault("addon_units",0L)).longValue());
  }
  static void close(Store store, Map<String,Object> w) {
    if("Free".equals(w.get("plan")) || w.get("subscription_id")==null) return;
    BigDecimal amount=accrued(w).setScale(2,RoundingMode.DOWN);
    store.db.update("INSERT INTO usage_charges(id,workspace_id,subscription_id,period_start,period_end,kind,units,amount,status) VALUES(?,?,?,?,?,'overage',?,?,?) ON CONFLICT DO NOTHING",UUID.randomUUID(),w.get("id"),w.get("subscription_id"),w.get("period_start"),w.get("period_end"),w.get("used_units"),amount,amount.signum()==0?"paid":"ready");
  }
  static final String PREFIX="MeterTrail usage ";
  static String marker(UUID id) { return PREFIX+id; }
  static UUID reference(JsonNode d) {
    String custom=d.path("custom_data").path("metertrail_charge").asText("");
    if(!custom.isBlank()) return Support.uuid(custom);
    for(JsonNode item:d.path("items")) {
      String description=item.path("price").path("description").asText();
      if(description.startsWith(PREFIX)) return Support.uuid(description.substring(PREFIX.length()));
    }
    return null;
  }
  static void transaction(Store store,Map<String,Object> w,JsonNode d,Instant occurred) {
    UUID reference=reference(d);if(reference==null)return;
    var rows=store.queries().queryForList("SELECT * FROM usage_charges WHERE id=? AND workspace_id=? FOR UPDATE",reference,w.get("id"));
    if(rows.isEmpty())throw new ApiError(400,"UNKNOWN_USAGE_CHARGE");
    var c=rows.get(0);
    if(c.get("transaction_id")!=null && !c.get("transaction_id").equals(d.path("id").asText()))throw new ApiError(409,"CHARGE_TRANSACTION_CONFLICT");
    boolean matches=false;
    for(JsonNode item:d.path("items")) {
      JsonNode p=item.path("price");
      if(marker(reference).equals(p.path("description").asText()) && item.path("quantity").asInt()==1 && p.path("billing_cycle").isNull() && "USD".equals(p.path("unit_price").path("currency_code").asText()) && new BigDecimal(p.path("unit_price").path("amount").asText("-1")).compareTo(((BigDecimal)c.get("amount")).movePointRight(2))==0)matches=true;
    }
    if(!matches || d.path("items").size()!=1 || !"USD".equals(d.path("currency_code").asText()))throw new ApiError(400,"USAGE_CHARGE_MISMATCH");
    if(c.get("provider_updated_at")!=null && !occurred.isAfter(((Timestamp)c.get("provider_updated_at")).toInstant()))return;
    String provider=d.path("status").asText();
    String state="completed".equals(provider)?"paid":Set.of("canceled","past_due").contains(provider)?"failed":"submitted";
    if((boolean)c.get("revoked"))state="refunded";
    else if(Set.of("paid","refund_required").contains((String)c.get("status")))state=(String)c.get("status");
    store.db.update("UPDATE usage_charges SET transaction_id=?,status=?,provider_updated_at=?,checkout_url=coalesce(?,checkout_url) WHERE id=?",d.path("id").asText(),state,Timestamp.from(occurred),d.path("checkout").hasNonNull("url")?d.path("checkout").path("url").asText():null,reference);
    if("paid".equals(state) && "addon".equals(c.get("kind")) && !(boolean)c.get("credited") && !(boolean)c.get("revoked")) {
      boolean current=c.get("period_start").equals(w.get("period_start")) && ((Timestamp)w.get("period_end")).toInstant().isAfter(Instant.now()) && "active".equals(w.get("subscription_status"));
      if(current) {
        store.db.update("UPDATE workspaces SET addon_units=addon_units+? WHERE id=?",c.get("units"),w.get("id"));
        store.db.update("UPDATE usage_charges SET credited=true WHERE id=?",reference);
      } else store.db.update("UPDATE usage_charges SET status='refund_required' WHERE id=?",reference);
    }
  }
  static void refund(Store store,Map<String,Object> w,JsonNode d) {
    if(!"refund".equals(d.path("action").asText()) || !Set.of("approved","issued").contains(d.path("status").asText()))return;
    for(var c:store.queries().queryForList("SELECT * FROM usage_charges WHERE transaction_id=? AND workspace_id=? FOR UPDATE",d.path("transaction_id").asText(),w.get("id"))) {
      if("addon".equals(c.get("kind")) && !(boolean)c.get("revoked")) {
        if((boolean)c.get("credited") && c.get("period_start").equals(w.get("period_start")))store.db.update("UPDATE workspaces SET addon_units=greatest(0,addon_units-?) WHERE id=?",c.get("units"),w.get("id"));
        store.db.update("UPDATE usage_charges SET revoked=true,status='refunded' WHERE id=?",c.get("id"));
      }
    }
  }
}
