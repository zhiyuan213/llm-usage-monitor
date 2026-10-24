package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkerLoopTest {
  Worker worker(boolean enabled) {
    return spy(new Worker(null, null, null, enabled, "unused@example.test"));
  }

  @Test
  void busySlicesContinueButEmptyQueueBacksOffWithoutDatabasePolling() {
    var worker = worker(true);
    doReturn(true, false, false).when(worker).drain(anyInt(), anyLong());
    var clock = new AtomicLong();
    var loop = new WorkerLoop(worker, 1000, 250, 300, 1000, new SimpleMeterRegistry(), clock::get);
    loop.poll(); // Busy slice: do not impose the old 300ms gap.
    loop.poll(); // Next slice finds no work.
    loop.poll(); // Idle backoff: no DB call.
    verify(worker, times(2)).drain(1000, TimeUnit.MILLISECONDS.toNanos(250));
    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(300));
    loop.poll();
    verify(worker, times(3)).drain(anyInt(), anyLong());
  }

  @Test
  void databaseFailureBacksOffAndRemainsVisible() {
    var worker = worker(true);
    doThrow(new IllegalStateException("synthetic database failure"))
        .doReturn(false)
        .when(worker)
        .drain(anyInt(), anyLong());
    var clock = new AtomicLong();
    var loop = new WorkerLoop(worker, 1000, 250, 300, 1000, new SimpleMeterRegistry(), clock::get);
    assertThrows(IllegalStateException.class, loop::poll);
    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(999));
    loop.poll();
    verify(worker).drain(anyInt(), anyLong());
    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
    loop.poll();
    verify(worker, times(2)).drain(anyInt(), anyLong());
  }

  @Test
  void disabledWorkerDoesNotTouchDatabase() {
    var worker = worker(false);
    new WorkerLoop(worker, 1000, 250, 300, 1000, new SimpleMeterRegistry(), System::nanoTime)
        .poll();
    verify(worker, never()).drain(anyInt(), anyLong());
  }
}
