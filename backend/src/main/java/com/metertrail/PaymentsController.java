package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
class PaymentsController {
  static BigDecimal fromMinorUnits(String amount, String currency) {
    try {
      int digits = Currency.getInstance(currency).getDefaultFractionDigits();
      Support.require(digits >= 0, "INVALID_CURRENCY");
      return new BigDecimal(amount).movePointLeft(digits);
    } catch (IllegalArgumentException e) {
      throw new ApiError(400, "INVALID_PAYMENT_AMOUNT");
    }
  }

  final Store store;
  final String secret, apiKey, core, pro, url;
  final MeterRegistry metrics;
  final CheckoutConfiguration checkoutConfiguration;
  final String publicUrl;
  final RestClient paddleClient;
  @org.springframework.beans.factory.annotation.Autowired(required=false) CreemPayments creem;

  PaymentsController(
      Store store,
      MeterRegistry metrics,
      CheckoutConfiguration checkoutConfiguration,
      @Value("${metertrail.public-url}") String publicUrl,
      @Value("${metertrail.paddle-secret}") String secret,
      @Value("${metertrail.paddle-api-key}") String apiKey,
      @Value("${metertrail.paddle-core-price}") String core,
      @Value("${metertrail.paddle-pro-price}") String pro,
      @Value("${metertrail.paddle-url}") String url) {
    var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(
        java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    requestFactory.setReadTimeout(Duration.ofSeconds(15));
    this.paddleClient = RestClient.builder().requestFactory(requestFactory).build();
    this.store = store;
    this.checkoutConfiguration = checkoutConfiguration;
    this.publicUrl = publicUrl.replaceAll("/+$", "");
    this.metrics = metrics;
    this.secret = secret;
    this.apiKey = apiKey;
    this.core = core;
    this.pro = pro;
    this.url = url;
  }

  // Other beans must invoke methods: direct field reads bypass the Spring transaction proxy.
  boolean reconciliationEnabled() {
    return apiKey != null && !apiKey.isBlank();
  }

  JsonNode readSubscription(String subscriptionId) {
    if (!reconciliationEnabled()) throw new ApiError(503, "PAYMENTS_NOT_CONFIGURED");
    return paddleClient
        .get()
        .uri(url + "/subscriptions/" + subscriptionId)
        .header("Authorization", "Bearer " + apiKey)
        .retrieve()
        .body(JsonNode.class);
  }

  String planPrice(String plan) {
    return plan.equals("Core") ? core : pro;
  }

  JsonNode previewSubscription(String subscriptionId, Map<String, Object> body) {
    return patchSubscription(subscriptionId + "/preview", body);
  }

  JsonNode patchSubscription(String subscriptionId, Map<String, Object> body) {
    if (!reconciliationEnabled()) throw new ApiError(503, "PAYMENTS_NOT_CONFIGURED");
    return paddleClient.patch().uri(url + "/subscriptions/" + subscriptionId)
        .header("Authorization", "Bearer " + apiKey).body(body).retrieve().body(JsonNode.class);
  }

  JsonNode readUsageTransaction(String id) {
    return paddleClient.get().uri(url+"/transactions/"+id).header("Authorization","Bearer "+apiKey).retrieve().body(JsonNode.class);
  }
  JsonNode usageTransactions(String customer, String after, Instant created) {
    return paddleClient.get().uri(url+"/transactions?customer_id="+customer+"&per_page=30&order_by=id[ASC]&created_at[GTE]="+created.minusSeconds(300)+(after.isBlank()?"":"&after="+after))
        .header("Authorization","Bearer "+apiKey).retrieve().body(JsonNode.class);
  }

  JsonNode postUsage(String path, Map<String,Object> body) {
    if (!reconciliationEnabled()) throw new ApiError(503,"PAYMENTS_NOT_CONFIGURED");
    return paddleClient.post().uri(url+path).header("Authorization","Bearer "+apiKey).body(body).retrieve().body(JsonNode.class);
  }

  @PostMapping("/api/billing/checkout")
  Map<String, String> checkout(@RequestBody JsonNode b, HttpServletRequest req) {
    String plan = b.path("plan").asText();
    Support.require(Set.of("Core", "Pro").contains(plan), "INVALID_PLAN");
    if(creem!=null && creem.enabled())return creem.checkout(plan,req);
    String price = plan.equals("Core") ? core : pro;
    if (apiKey.isBlank() || price.isBlank() || !checkoutConfiguration.enabled) throw new ApiError(503, "PAYMENTS_NOT_CONFIGURED");
    var w = store.workspace(SessionFilter.uid(req));
    if (w.get("subscription_id") != null && !SubscriptionState.ended(w)) throw new ApiError(409, "USE_BILLING_PORTAL");
    UUID intent = UUID.randomUUID();
    store.db.update(
        "INSERT INTO checkout_intents(id,workspace_id,plan,previous_subscription_id) VALUES(?,?,?,?)",
        intent,
        w.get("id"),
        plan,w.get("subscription_id"));
    JsonNode result =
        paddleClient
            .post()
            .uri(url + "/transactions")
            .header("Authorization", "Bearer " + apiKey)
            .body(
                Map.of(
                    "items",
                    List.of(Map.of("price_id", price, "quantity", 1)),
                    "checkout",
                    Map.of("url", publicUrl + "/pay"),
                    "collection_mode",
                    "automatic",
                    "custom_data",
                    Map.of("metertrail_intent", intent)))
            .retrieve()
            .body(JsonNode.class);
    if (result == null || !result.path("data").path("checkout").hasNonNull("url"))
      throw new ApiError(503, "CHECKOUT_UNAVAILABLE");
    store.db.update(
        "UPDATE checkout_intents SET transaction_id=? WHERE id=?",
        result.path("data").path("id").asText(),
        intent);
    return Map.of("url", result.path("data").path("checkout").path("url").asText());
  }

  static boolean signature(String secret, String header, String body, Instant now) {
    if (secret.isBlank() || header == null) return false;
    String ts = null;
    List<String> signatures = new ArrayList<>();
    for (String part : header.split(";")) {
      String[] kv = part.trim().split("=", 2);
      if (kv.length == 2) {
        if (kv[0].equals("ts")) ts = kv[1];
        if (kv[0].equals("h1")) signatures.add(kv[1]);
      }
    }
    try {
      if (Math.abs(now.getEpochSecond() - Long.parseLong(ts)) > 300) return false;
      String expected = Support.hmac(secret, ts + ":" + body);
      return signatures.stream().anyMatch(s -> Support.equal(expected, s));
    } catch (Exception e) {
      return false;
    }
  }

  @PostMapping("/api/billing/portal")
  Map<String, String> portal(HttpServletRequest req) {
    var w = store.workspace(SessionFilter.uid(req));
    if(creem!=null && creem.owns(w))return creem.portal(w);
    if (apiKey.isBlank() || w.get("customer_id") == null)
      throw new ApiError(503, "PAYMENTS_NOT_CONFIGURED");
    JsonNode result =
        paddleClient
            .post()
            .uri(url + "/customers/" + w.get("customer_id") + "/portal-sessions")
            .header("Authorization", "Bearer " + apiKey)
            .body(Map.of())
            .retrieve()
            .body(JsonNode.class);
    if (result == null || !result.path("data").path("urls").path("general").hasNonNull("overview"))
      throw new ApiError(503, "PORTAL_UNAVAILABLE");
    return Map.of(
        "url", result.path("data").path("urls").path("general").path("overview").asText());
  }

  @PostMapping("/webhooks/paddle")
  @Transactional
  Map<String, String> webhook(
      @RequestHeader(value = "Paddle-Signature", required = false) String header,
      @RequestBody String body) {
    if (secret.isBlank()) throw new ApiError(503, "PAYMENTS_NOT_CONFIGURED");
    if (!signature(secret, header, body, Instant.now()))
      throw new ApiError(401, "INVALID_SIGNATURE");
    return applyEvent(Support.parse(body));
  }

  Map<String, String> applyEvent(JsonNode b) {
    String id = Support.text(b, "event_id", 128), type = Support.text(b, "event_type", 128);
    Instant occurred = Instant.parse(b.path("occurred_at").asText());
    int inserted =
        store.db.update(
            "INSERT INTO webhook_events(event_id,event_type,occurred_at) VALUES(?,?,?) ON CONFLICT"
                + " DO NOTHING",
            id,
            type,
            Timestamp.from(occurred));
    if (inserted == 0) return Map.of("status", "duplicate");
    JsonNode d = b.path("data");
    String sub =
        type.startsWith("subscription.")
            ? d.path("id").asText()
            : d.path("subscription_id").asText();
    var workspaces =
        store.queries().queryForList("SELECT * FROM workspaces WHERE payment_provider='paddle' AND subscription_id=? FOR UPDATE", sub);
    UUID charge = type.startsWith("transaction.") ? ChargeLedger.reference(d) : null;
    if (charge != null) workspaces = store.queries().queryForList("SELECT w.* FROM workspaces w JOIN usage_charges c ON c.workspace_id=w.id WHERE c.id=? AND w.payment_provider='paddle' FOR UPDATE OF w",charge);
    if (workspaces.isEmpty() && d.path("custom_data").hasNonNull("metertrail_intent")) {
      UUID intent = Support.uuid(d.path("custom_data").path("metertrail_intent").asText());
      workspaces =
          store.queries().queryForList(
              "SELECT w.* FROM workspaces w JOIN checkout_intents c ON c.workspace_id=w.id WHERE"
                  + " c.id=? AND c.payment_provider='paddle' AND ((w.payment_provider='paddle' AND w.subscription_id=?) OR (w.subscription_id IS NOT DISTINCT FROM c.previous_subscription_id AND (w.subscription_id IS NULL OR w.subscription_status IN ('canceled','expired')))) FOR UPDATE OF w",
              intent,sub);
    }
    if (workspaces.isEmpty() && type.startsWith("adjustment."))
      workspaces =
          store.queries().queryForList(
              "SELECT w.* FROM workspaces w JOIN payments p ON p.workspace_id=w.id WHERE p.id=? FOR"
                  + " UPDATE OF w",
              d.path("transaction_id").asText());
    if (workspaces.isEmpty()) return Map.of("status", "ignored");
    var w = workspaces.get(0);
    UUID workspace = (UUID) w.get("id");
    if (type.startsWith("transaction.")) {
      ChargeLedger.transaction(store,w,d,occurred);
      String amount = d.path("details").path("totals").path("grand_total").asText("0");
      store.db.update(
          "INSERT INTO payments(id,workspace_id,status,amount,currency,updated_at)"
              + " VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
              + " status=excluded.status,amount=excluded.amount,currency=excluded.currency,updated_at=excluded.updated_at"
              + " WHERE payments.updated_at<excluded.updated_at",
          d.path("id").asText(),
          workspace,
          d.path("status").asText(),
          fromMinorUnits(amount, d.path("currency_code").asText()),
          d.path("currency_code").asText(),
          Timestamp.from(occurred));
      store.audit(null, workspace, "payment." + type, id, true, null);
      return Map.of("status", "processed");
    }
    if (type.startsWith("adjustment.")) {
      ChargeLedger.refund(store,w,d);
      store.db.update(
          "INSERT INTO"
              + " payment_adjustments(id,workspace_id,transaction_id,action,status,amount,currency,updated_at)"
              + " VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
              + " status=excluded.status,amount=excluded.amount,updated_at=excluded.updated_at"
              + " WHERE payment_adjustments.updated_at<excluded.updated_at",
          d.path("id").asText(),
          workspace,
          d.path("transaction_id").asText(),
          d.path("action").asText(),
          d.path("status").asText(),
          fromMinorUnits(
              d.path("totals").path("total").asText("0"), d.path("currency_code").asText()),
          d.path("currency_code").asText(),
          Timestamp.from(occurred));
      store.audit(null, workspace, "payment." + type, id, true, null);
      return Map.of("status", "processed");
    }
    if (w.get("payment_updated_at") != null
        && !occurred.isAfter(((Timestamp) w.get("payment_updated_at")).toInstant()))
      return Map.of("status", "stale");
    if (type.startsWith("subscription.")) {
      if (d.hasNonNull("customer_id"))
        store.db.update(
            "UPDATE workspaces SET customer_id=? WHERE id=?",
            d.path("customer_id").asText(),
            workspace);
      String status = d.path("status").asText();
      String price = d.path("items").path(0).path("price").path("id").asText();
      String plan =
          price.equals(core) && !core.isBlank()
              ? "Core"
              : price.equals(pro) && !pro.isBlank() ? "Pro" : null;
      if (plan == null) throw new ApiError(400, "UNKNOWN_PRICE");
      JsonNode period = d.path("current_billing_period");
      if (status.equals("active")
          && period.hasNonNull("starts_at")
          && period.hasNonNull("ends_at")) {
        Instant start = Instant.parse(period.path("starts_at").asText()),
            end = Instant.parse(period.path("ends_at").asText());
        if (!end.isAfter(start)) throw new ApiError(400, "INVALID_BILLING_PERIOD");
        Instant previousStart = ((Timestamp) w.get("period_start")).toInstant();
        if (start.isBefore(previousStart) && !"Free".equals(w.get("plan"))) return Map.of("status", "stale");
        boolean newPeriod = start.isAfter(previousStart) || "Free".equals(w.get("plan"));
        if(newPeriod) ChargeLedger.close(store,w);
        store.db.update(
            "UPDATE workspaces SET"
                + " payment_provider='paddle',retention_plan=NULL,retention_grace_until=NULL,plan=?,subscription_id=?,subscription_status=?,period_start=?,period_end=?,used_units=CASE"
                + " WHEN ? THEN 0 ELSE used_units END,addon_units=CASE WHEN ? THEN 0 ELSE addon_units END,overage_units=CASE WHEN ? THEN 0 ELSE overage_units END,addon_consumed=CASE WHEN ? THEN 0 ELSE addon_consumed END,payment_updated_at=? WHERE id=?",
            plan,
            sub,
            status,
            Timestamp.from(start),
            Timestamp.from(end),
            newPeriod,
            newPeriod,
            newPeriod,
            newPeriod,
            Timestamp.from(occurred),
            workspace);
      } else {
        if(java.util.Set.of("canceled","expired").contains(status)) SubscriptionRetention.endPaidAccess(store,w,occurred);
        store.db.update(
            "UPDATE workspaces SET retention_grace_until=coalesce(retention_grace_until,least(period_end,?::timestamptz)+interval '30 days'),subscription_status=?,auto_overage=false,payment_updated_at=?"
                + " WHERE id=?",
            Timestamp.from(occurred),
            status,
            Timestamp.from(occurred),
            workspace);
      }
      store.db.update("UPDATE workspaces SET scheduled_change=?::jsonb WHERE id=?", d.path("scheduled_change").isMissingNode() ? "null" : d.path("scheduled_change").toString(), workspace);
      if (status.equals("active")) store.db.update("UPDATE subscription_change_quotes SET status='confirmed' WHERE workspace_id=? AND subscription_id=? AND plan=? AND status IN ('processing','submitted') AND created_at<=?", workspace, sub, plan, Timestamp.from(occurred));
      store.audit(null, workspace, "payment." + type, id, true, null);
    }
    metrics.counter("metertrail_webhooks_processed").increment();
    return Map.of("status", "processed");
  }
}
