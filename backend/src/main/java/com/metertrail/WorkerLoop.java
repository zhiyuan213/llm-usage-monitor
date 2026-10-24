package com.metertrail;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class WorkerLoop {
  final Worker worker;
  final int maxBatches;
  final long sliceNanos, idleNanos, failureNanos;
  final MeterRegistry metrics;
  final LongSupplier clock;
  long pausedAt, pauseNanos;

  @Autowired
  WorkerLoop(
      Worker worker,
      @Value("${metertrail.worker.max-batches-per-slice:1000}") int maxBatches,
      @Value("${metertrail.worker.slice-ms:250}") long sliceMs,
      @Value("${metertrail.worker.idle-poll-ms:300}") long idleMs,
      @Value("${metertrail.worker.failure-backoff-ms:1000}") long failureMs,
      MeterRegistry metrics) {
    this(worker, maxBatches, sliceMs, idleMs, failureMs, metrics, System::nanoTime);
  }

  WorkerLoop(
      Worker worker,
      int maxBatches,
      long sliceMs,
      long idleMs,
      long failureMs,
      MeterRegistry metrics,
      LongSupplier clock) {
    if (maxBatches < 1 || sliceMs < 1 || idleMs < 1 || failureMs < 1)
      throw new IllegalArgumentException("Worker polling budgets must be positive");
    this.worker = worker;
    this.maxBatches = maxBatches;
    this.sliceNanos = TimeUnit.MILLISECONDS.toNanos(sliceMs);
    this.idleNanos = TimeUnit.MILLISECONDS.toNanos(idleMs);
    this.failureNanos = TimeUnit.MILLISECONDS.toNanos(failureMs);
    this.metrics = metrics;
    this.clock = clock;
  }

  @Scheduled(
      scheduler = "ingestWorkerScheduler",
      fixedDelayString = "${metertrail.worker.busy-poll-ms:10}")
  public void poll() {
    if (!worker.enabled || (pauseNanos > 0 && clock.getAsLong() - pausedAt < pauseNanos)) return;
    try {
      boolean busy = worker.drain(maxBatches, sliceNanos);
      pauseNanos = busy ? 0 : idleNanos;
      metrics.counter("metertrail_worker_slices", "outcome", busy ? "busy" : "idle").increment();
    } catch (RuntimeException failure) {
      pauseNanos = failureNanos;
      metrics.counter("metertrail_worker_slices", "outcome", "error").increment();
      throw failure;
    } finally {
      pausedAt = clock.getAsLong();
    }
  }
}
