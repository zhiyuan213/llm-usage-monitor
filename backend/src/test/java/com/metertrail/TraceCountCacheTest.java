package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class TraceCountCacheTest {
  @Test
  void concurrentMissesShareOneQuery() throws Exception {
    var cache = new TraceCountCache();
    var clients = Executors.newFixedThreadPool(12);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    UUID project = UUID.randomUUID(), owner = UUID.randomUUID();
    try {
      var results = new ArrayList<Future<Long>>();
      for (int i = 0; i < 12; i++)
        results.add(
            clients.submit(
                () ->
                    cache.get(
                        project,
                        owner,
                        () -> {
                          calls.incrementAndGet();
                          started.countDown();
                          try {
                            assertTrue(release.await(3, TimeUnit.SECONDS));
                          } catch (InterruptedException failure) {
                            throw new RuntimeException(failure);
                          }
                          return 42;
                        })));
      assertTrue(started.await(3, TimeUnit.SECONDS));
      assertEquals(1, calls.get());
      release.countDown();
      for (var result : results) assertEquals(42, result.get(3, TimeUnit.SECONDS));
      assertEquals(1, calls.get());
    } finally {
      release.countDown();
      clients.shutdownNow();
      cache.destroy();
    }
  }

  @Test
  void refreshAheadKeepsTheOneSecondSnapshotTtl() {
    var clock = new AtomicLong();
    var calls = new AtomicInteger();
    var cache = new TraceCountCache(clock::get, Runnable::run);
    UUID project = UUID.randomUUID(), owner = UUID.randomUUID();
    assertEquals(1, cache.get(project, owner, calls::incrementAndGet));
    clock.set(400_000_000);
    assertEquals(1, cache.get(project, owner, calls::incrementAndGet));
    assertEquals(1, calls.get());
    clock.set(800_000_000);
    assertEquals(1, cache.get(project, owner, calls::incrementAndGet));
    assertEquals(2, calls.get());
    assertEquals(2, cache.get(project, owner, calls::incrementAndGet));
    clock.set(1_900_000_000);
    assertEquals(3, cache.get(project, owner, calls::incrementAndGet));
  }

  @Test
  void failureNeverBecomesAZeroCountOrAnExpiredSuccess() {
    var clock = new AtomicLong();
    var cache = new TraceCountCache(clock::get, Runnable::run);
    UUID project = UUID.randomUUID(), owner = UUID.randomUUID();
    assertEquals(
        "COUNT_QUERY_UNAVAILABLE",
        assertThrows(
                ApiError.class,
                () ->
                    cache.get(
                        project,
                        owner,
                        () -> {
                          throw new IllegalStateException();
                        }))
            .code);
    assertEquals(9, cache.get(project, owner, () -> 9));
    clock.set(2_000_000_000);
    assertThrows(
        ApiError.class,
        () ->
            cache.get(
                project,
                owner,
                () -> {
                  throw new IllegalStateException();
                }));
    assertEquals(12, cache.get(project, owner, () -> 12));
  }

  @Test
  void ownersCannotShareCachedCounts() {
    var cache = new TraceCountCache(() -> 0, Runnable::run);
    UUID project = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
    assertEquals(7, cache.get(project, a, () -> 7));
    assertEquals(0, cache.get(project, b, () -> 0));
    assertEquals(7, cache.get(project, a, () -> 99));
  }

  @Test
  void fullRefreshQueueFailsInsteadOfReturningAnInventedCount() {
    var cache =
        new TraceCountCache(
            () -> 0,
            r -> {
              throw new RejectedExecutionException();
            });
    assertEquals(
        "COUNT_QUERY_BUSY",
        assertThrows(
                ApiError.class, () -> cache.get(UUID.randomUUID(), UUID.randomUUID(), () -> 42))
            .code);
    assertTrue(cache.flights.isEmpty());
    assertTrue(cache.values.isEmpty());
  }
}
