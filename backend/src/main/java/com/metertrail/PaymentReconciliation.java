package com.metertrail;

import com.fasterxml.jackson.databind.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class PaymentReconciliation {
  final PaymentsController payments;
  final Store store;
  final TransactionTemplate tx;
  final MeterRegistry metrics;

  PaymentReconciliation(
      PaymentsController payments, Store store, TransactionTemplate tx, MeterRegistry metrics) {
    this.payments = payments;
    this.store = store;
    this.tx = tx;
    this.metrics = metrics;
  }

  @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
  void reconcile() {
    if (!payments.reconciliationEnabled()) return;
    for (var row :
        store.db.queryForList(
            "SELECT subscription_id FROM workspaces WHERE subscription_id IS NOT NULL")) {
      try {
        JsonNode response = payments.readSubscription((String) row.get("subscription_id"));
        if (response == null) throw new IllegalStateException();
        JsonNode data = response.path("data");
        String at = data.path("updated_at").asText();
        JsonNode event =
            new ObjectMapper()
                .valueToTree(
                    Map.of(
                        "event_id",
                        "reconcile_" + Support.hash(data.path("id").asText() + at),
                        "event_type",
                        "subscription.updated",
                        "occurred_at",
                        at,
                        "data",
                        data));
        tx.executeWithoutResult(s -> payments.applyEvent(event));
        metrics.counter("metertrail_reconciliation", "result", "success").increment();
      } catch (Exception e) {
        metrics.counter("metertrail_reconciliation", "result", "failure").increment();
      }
    }
  }
}
