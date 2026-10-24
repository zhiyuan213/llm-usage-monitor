package com.metertrail;

import io.micrometer.core.instrument.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class Metrics {
  final Store store;
  final AtomicLong backlog = new AtomicLong(),
      oldest = new AtomicLong(),
      difference = new AtomicLong(),
      outbox = new AtomicLong(),
      quarantined = new AtomicLong();

  Metrics(Store store, MeterRegistry registry) {
    this.store = store;
    registry.gauge("metertrail_pending_batches", backlog);
    registry.gauge("metertrail_oldest_pending_seconds", oldest);
    registry.gauge("metertrail_ledger_difference", difference);
    registry.gauge("metertrail_pending_notifications", outbox);
    registry.gauge("metertrail_quarantined_batches", quarantined);
  }

  @Scheduled(fixedDelay = 15000)
  void collect() {
    quarantined.set(
        store.queries().queryForObject(
            "SELECT count(*) FROM ingest_batches WHERE status='quarantined'", Long.class));
    backlog.set(
        store.queries().queryForObject(
            "SELECT count(*) FROM ingest_batches WHERE status='pending'", Long.class));
    oldest.set(
        store.queries().queryForObject(
            "SELECT coalesce(extract(epoch FROM now()-min(created_at)),0)::bigint FROM"
                + " ingest_batches WHERE status='pending'",
            Long.class));
    difference.set(
        store.queries().queryForObject(
            "SELECT coalesce(sum(abs(w.used_units-coalesce(l.units,0))),0)::bigint FROM workspaces"
                + " w LEFT JOIN LATERAL(SELECT sum(units) AS units FROM usage_ledger WHERE"
                + " workspace_id=w.id AND period_start=w.period_start) l ON true",
            Long.class));
    outbox.set(
        store.queries().queryForObject("SELECT count(*) FROM outbox WHERE sent_at IS NULL", Long.class));
  }
}
