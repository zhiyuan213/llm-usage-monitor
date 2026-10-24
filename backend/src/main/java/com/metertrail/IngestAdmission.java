package com.metertrail;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bound POST database users before authentication queries can exhaust the shared pool. */
@Component
class IngestAdmission {
  final Semaphore permits;
  final int limit;
  final MeterRegistry metrics;

  IngestAdmission(
      DataSource source,
      MeterRegistry metrics,
      @Value("${metertrail.ingest.max-inflight:4}") int configuredLimit) {
    if (configuredLimit < 1)
      throw new IllegalArgumentException("Ingest concurrency must be positive");
    int poolSize = source instanceof HikariDataSource hikari ? hikari.getMaximumPoolSize() : -1;
    // Reserve at least two connection slots where the configured pool size permits it.
    this.limit =
        poolSize > 0 ? Math.min(configuredLimit, Math.max(1, poolSize - 2)) : configuredLimit;
    this.permits = new Semaphore(limit);
    this.metrics = metrics;
    metrics.gauge(
        "metertrail_ingest_inflight", this, gate -> gate.limit - gate.permits.availablePermits());
    metrics.gauge("metertrail_ingest_inflight_limit", this, gate -> gate.limit);
  }

  Lease enter() {
    if (!permits.tryAcquire()) {
      unavailable("INGEST_BUSY");
      throw new ApiError(503, "INGEST_BUSY");
    }
    return new Lease();
  }

  void unavailable(String code) {
    String reason =
        Set.of("INGEST_BUSY", "INGEST_BACKLOG_FULL", "RATE_LIMIT_UNAVAILABLE",
            "INGEST_QUEUE_FULL","INGEST_QUEUE_UNAVAILABLE","INGEST_ACCEPTANCE_UNCERTAIN",
            "INGEST_PROCESSING_FAILED","INGEST_SHUTTING_DOWN").contains(code)
            ? code
            : "OTHER";
    metrics.counter("metertrail_ingest_unavailable", "code", reason).increment();
  }

  final class Lease implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) permits.release();
    }
  }
}
