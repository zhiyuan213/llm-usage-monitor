package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;

@RestController
class SubscriptionChanges {
  final PaymentsController payments;
  final Store store;
  SubscriptionChanges(PaymentsController payments, Store store) {
    this.payments = payments;
    this.store = store;
  }

  Map<String, Object> body(String plan) {
    if (!Set.of("Core", "Pro").contains(plan)) throw new ApiError(400, "INVALID_PLAN");
    String price = payments.planPrice(plan);
    if (price.isBlank()) throw new ApiError(503, "PAYMENTS_NOT_CONFIGURED");
    return Map.of("items", List.of(Map.of("price_id", price, "quantity", 1)),
        "proration_billing_mode", "prorated_immediately", "on_payment_failure", "prevent_change");
  }

  JsonNode subscription(Map<String, Object> workspace) {
    if("creem".equals(workspace.get("payment_provider")))throw new ApiError(409,"USE_BILLING_PORTAL");
    Object id = workspace.get("subscription_id");
    if (id == null) throw new ApiError(409, "SUBSCRIPTION_REQUIRED");
    JsonNode response = payments.readSubscription(id.toString());
    JsonNode sub = response == null ? null : response.get("data");
    if (sub == null || !sub.path("status").asText().equals("active")
        || sub.hasNonNull("scheduled_change") || sub.path("items").size() != 1)
      throw new ApiError(409, "SUBSCRIPTION_CHANGE_UNAVAILABLE");
    return sub;
  }

  JsonNode preview(String sub, String plan) {
    JsonNode response = payments.previewSubscription(sub, body(plan));
    if (response == null || !response.hasNonNull("data")) throw new ApiError(503, "PLAN_PREVIEW_UNAVAILABLE");
    JsonNode data = response.get("data");
    if (!data.path("recurring_transaction_details").path("totals").hasNonNull("total")
        || !data.path("recurring_transaction_details").path("totals").hasNonNull("currency_code"))
      throw new ApiError(503, "PLAN_PREVIEW_UNAVAILABLE");
    return data;
  }

  // Only compare the amounts the customer sees, not provider timestamps or generated IDs.
  static JsonNode totals(JsonNode preview) {
    var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    for (String field : List.of("immediate_transaction", "next_transaction", "recurring_transaction_details")) {
      JsonNode transaction = preview.path(field);
      result.set(field, field.equals("recurring_transaction_details") ? transaction.path("totals") : transaction.path("details").path("totals"));
    }
    return result;
  }

  @PostMapping("/api/billing/change-preview")
  Map<String, Object> preview(@RequestBody JsonNode input, HttpServletRequest request) {
    var workspace = store.workspace(SessionFilter.uid(request));
    if (!store.queries().queryForList("SELECT id FROM subscription_change_quotes WHERE workspace_id=? AND status IN ('processing','submitted')", workspace.get("id")).isEmpty())
      throw new ApiError(409, "PLAN_CHANGE_PENDING");
    String plan = input.path("plan").asText();
    body(plan);
    JsonNode sub = subscription(workspace);
    String currentPrice = sub.path("items").get(0).path("price").path("id").asText();
    if (currentPrice.equals(payments.planPrice(plan))) throw new ApiError(409, "PLAN_ALREADY_SELECTED");
    JsonNode result = preview(sub.path("id").asText(), plan);
    UUID id = UUID.randomUUID();
    Instant expires = Instant.now().plusSeconds(300);
    store.db.update("INSERT INTO subscription_change_quotes(id,workspace_id,subscription_id,plan,provider_updated_at,preview,expires_at) VALUES(?,?,?,?,?,?::jsonb,?)",
        id, workspace.get("id"), sub.path("id").asText(), plan, sub.path("updated_at").asText(), result.toString(), Timestamp.from(expires));
    return Map.of("quoteId", id, "plan", plan, "expiresAt", expires.toString(), "totals", totals(result));
  }

  @PostMapping("/api/billing/change")
  Map<String, String> change(@RequestBody JsonNode input, HttpServletRequest request) {
    var workspace = store.workspace(SessionFilter.uid(request));
    UUID id = Support.uuid(input.path("quoteId").asText());
    var quotes = store.queries().queryForList("SELECT * FROM subscription_change_quotes WHERE id=? AND workspace_id=?", id, workspace.get("id"));
    if (quotes.isEmpty()) throw new ApiError(404, "PLAN_QUOTE_NOT_FOUND");
    var quote = quotes.get(0);
    if (!quote.get("status").equals("ready") || !((Timestamp) quote.get("expires_at")).toInstant().isAfter(Instant.now()))
      throw new ApiError(409, "PLAN_QUOTE_EXPIRED");
    // Claim once before network I/O. Ambiguous timeouts remain pending; never blindly bill twice.
    synchronized (this) {
      if (!store.queries().queryForList("SELECT id FROM subscription_change_quotes WHERE workspace_id=? AND status IN ('processing','submitted')", workspace.get("id")).isEmpty())
        throw new ApiError(409, "PLAN_CHANGE_PENDING");
      if (store.db.update("UPDATE subscription_change_quotes SET status='processing' WHERE id=? AND status='ready'", id) != 1)
        throw new ApiError(409, "PLAN_CHANGE_PENDING");
    }
    boolean mutationStarted = false;
    try {
    JsonNode sub = subscription(workspace);
    String plan = quote.get("plan").toString(), subscription = quote.get("subscription_id").toString();
    if (!sub.path("id").asText().equals(subscription)
        || !sub.path("updated_at").asText().equals(quote.get("provider_updated_at"))
        || !totals(preview(subscription, plan)).equals(totals(Support.parse(quote.get("preview").toString()))))
      throw new ApiError(409, "PLAN_QUOTE_CHANGED");
      mutationStarted = true;
      payments.patchSubscription(subscription, body(plan));
      store.db.update("UPDATE subscription_change_quotes SET status='submitted' WHERE id=? AND status IN ('processing','submitted')", id);
      store.audit(SessionFilter.uid(request), (UUID) workspace.get("id"), "subscription.change", id.toString(), true, request);
      return Map.of("status", "pending");
    } catch (ApiError e) {
      store.db.update("UPDATE subscription_change_quotes SET status='failed' WHERE id=?", id);
      throw e;
    } catch (HttpClientErrorException e) {
      store.db.update("UPDATE subscription_change_quotes SET status='failed' WHERE id=?", id);
      throw new ApiError(409, "PLAN_CHANGE_REJECTED");
    } catch (org.springframework.web.client.RestClientException e) {
      if (!mutationStarted) store.db.update("UPDATE subscription_change_quotes SET status='failed' WHERE id=?", id);
      throw new ApiError(503, mutationStarted ? "PLAN_CHANGE_PENDING" : "PLAN_PREVIEW_UNAVAILABLE");
    }
  }
}
