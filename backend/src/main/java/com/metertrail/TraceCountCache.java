package com.metertrail;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Exact, owner-scoped counts with single-flight and refresh ahead of the existing one-second TTL.
 */
@Component
class TraceCountCache implements DisposableBean {
  record Scope(UUID project, UUID owner) {}

  record Entry(long value, long refreshAt, long expiresAt) {}

  static final long SECOND = 1_000_000_000L;
  final ConcurrentMap<Scope, Entry> values = new ConcurrentHashMap<>();
  final ConcurrentMap<Scope, CompletableFuture<Entry>> flights = new ConcurrentHashMap<>();
  final LongSupplier clock;
  final Executor executor;

  @Autowired
  TraceCountCache() {
    this(
        System::nanoTime,
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            r -> {
              var t = new Thread(r, "trace-count-refresh");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy()));
  }

  TraceCountCache(LongSupplier clock, Executor executor) {
    this.clock = clock;
    this.executor = executor;
  }

  long get(UUID project, UUID owner, LongSupplier load) {
    var key = new Scope(project, owner);
    long now = clock.getAsLong();
    var cached = values.get(key);
    if (cached != null && cached.expiresAt > now) {
      if (cached.refreshAt <= now) refresh(key, load);
      return cached.value;
    }
    try {
      return refresh(key, load).get(5, TimeUnit.SECONDS).value;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new ApiError(503, "COUNT_QUERY_UNAVAILABLE");
    } catch (TimeoutException failure) {
      throw new ApiError(503, "COUNT_QUERY_TIMEOUT");
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof RejectedExecutionException)
        throw new ApiError(503, "COUNT_QUERY_BUSY");
      throw new ApiError(503, "COUNT_QUERY_UNAVAILABLE");
    }
  }

  private CompletableFuture<Entry> refresh(Scope key, LongSupplier load) {
    var next = new CompletableFuture<Entry>();
    var current = flights.putIfAbsent(key, next);
    if (current != null) return current;
    long enqueued = clock.getAsLong();
    try {
      executor.execute(
          () -> {
            try {
              long started = clock.getAsLong();
              if (started - enqueued > 5 * SECOND)
                throw new RejectedExecutionException("Expired count refresh");
          var value = new Entry(load.getAsLong(), started + SECOND / 2, started + SECOND);
              values.put(key, value);
              if (values.size() > 4096)
                values.entrySet().removeIf(e -> e.getValue().expiresAt <= clock.getAsLong());
              flights.remove(key, next);
              next.complete(value);
            } catch (RuntimeException failure) {
              org.slf4j.LoggerFactory.getLogger(TraceCountCache.class)
                  .warn("Trace count refresh failed ({})", failure.getClass().getSimpleName());
              flights.remove(key, next);
              next.completeExceptionally(failure);
            } finally {
              flights.remove(key, next);
            }
          });
    } catch (RejectedExecutionException full) {
      flights.remove(key, next);
      next.completeExceptionally(full);
    }
    return next;
  }

  void invalidate(UUID project) {
    values.keySet().removeIf(k -> k.project.equals(project));
  }

  @Override
  public void destroy() {
    if (executor instanceof ExecutorService service) service.shutdownNow();
    flights
        .values()
        .forEach(
            f -> f.completeExceptionally(new RejectedExecutionException("Count refresh stopping")));
    flights.clear();
    values.clear();
  }
}
