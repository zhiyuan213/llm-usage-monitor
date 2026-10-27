package com.metertrail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, bounded ingress. No success receipt leaves the process before acceptance commits. */
@Component
@EnableConfigurationProperties(IngestQueueOptions.class)
class IngestPipeline implements SmartLifecycle {
  static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IngestPipeline.class);
  static final ObjectMapper JSON = new ObjectMapper();
  final Store store;
  final AggregatedIngest aggregate;
  final IngestQueueOptions options;
  final MeterRegistry metrics;
  final TransactionTemplate tx;
  final BlockingQueue<Ticket> incoming;
  final ConcurrentMap<UUID, Ticket> waiting = new ConcurrentHashMap<>();
  final Semaphore slots, wakeup = new Semaphore(0);
  final AtomicLong bufferedBytes = new AtomicLong(),
      queuedRequests = new AtomicLong(),
      queuedEvents = new AtomicLong(),
      queuedBytes = new AtomicLong();
  volatile boolean running;
  ExecutorService threads;

  final class Ticket {
    final UUID id = UUID.randomUUID();
    final Store.KeyScope scope;
    final int received, bytes;
    String payload;
    final AtomicBoolean payloadReleased = new AtomicBoolean();
    final CompletableFuture<AggregatedIngest.Result> result = new CompletableFuture<>();
    final CompletableFuture<Void> durable = new CompletableFuture<>();

    Ticket(Store.KeyScope scope, String payload, int received, int bytes) {
      this.scope = scope;
      this.payload = payload;
      this.received = received;
      this.bytes = bytes;
      result.whenComplete(
          (value, error) -> {
            waiting.remove(id, this);
            slots.release();
          });
    }

    void uncertain() {
      result.complete(error(503, "INGEST_ACCEPTANCE_UNCERTAIN"));
    }

    void releasePayload() {
      if (payloadReleased.compareAndSet(false, true)) {
        payload = null;
        bufferedBytes.addAndGet(-bytes);
      }
    }
  }

  record Finished(UUID block, Map<UUID, AggregatedIngest.Result> results) {}

  IngestPipeline(
      Store store,
      AggregatedIngest aggregate,
      IngestQueueOptions options,
      MeterRegistry metrics,
      PlatformTransactionManager transactions) {
    this.store = store;
    this.aggregate = aggregate;
    this.options = options;
    this.metrics = metrics;
    this.tx = new TransactionTemplate(transactions);
    incoming = new ArrayBlockingQueue<>(options.maxInflight());
    slots = new Semaphore(options.maxInflight());
    metrics.gauge("metertrail_ingest_buffered_bytes", bufferedBytes);
    metrics.gauge("metertrail_ingest_waiting_requests", waiting, Map::size);
    metrics.gauge("metertrail_ingest_queue_requests", queuedRequests);
    metrics.gauge("metertrail_ingest_queue_events", queuedEvents);
    metrics.gauge("metertrail_ingest_queue_bytes", queuedBytes);
  }

  boolean enabled() {
    return options.enabled();
  }

  Ticket submit(Store.KeyScope scope, JsonNode body) {
    body = Protocol.defaults(body);
    if (!running) throw new ApiError(503, "INGEST_SHUTTING_DOWN");
    if (!slots.tryAcquire()) throw new ApiError(503, "INGEST_BUSY");
    String payload = body.toString();
    int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > 1_048_576) {
      slots.release();
      throw new ApiError(413, "PAYLOAD_TOO_LARGE");
    }
    if (bufferedBytes.addAndGet(bytes) > options.maxBufferedBytes()) {
      bufferedBytes.addAndGet(-bytes);
      slots.release();
      throw new ApiError(503, "INGEST_BUSY");
    }
    var ticket = new Ticket(scope, payload, body.path("events").size(), bytes);
    waiting.put(ticket.id, ticket);
    if (!incoming.offer(ticket)) {
      ticket.releasePayload();
      ticket.result.complete(error(503, "INGEST_BUSY"));
      throw new ApiError(503, "INGEST_BUSY");
    }
    if (!running) {
      if (incoming.remove(ticket)) ticket.releasePayload();
      ticket.result.complete(error(503, "INGEST_SHUTTING_DOWN"));
      throw new ApiError(503, "INGEST_SHUTTING_DOWN");
    }
    return ticket;
  }

  static AggregatedIngest.Result error(int status, String code) {
    return new AggregatedIngest.Result(status, Map.of("code", code));
  }

  private void writer() {
    Ticket carry = null;
    while (running || carry != null || !incoming.isEmpty()) {
      var batch = new ArrayList<Ticket>();
      try {
        Ticket first = carry != null ? carry : incoming.poll(100, TimeUnit.MILLISECONDS);
        carry = null;
        if (first == null) continue;
        batch.add(first);
        int bytes = first.bytes;
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.coalesceMillis());
        while (batch.size() < options.writeBatchRequests()) {
          var next = incoming.peek();
          if (next != null && bytes + next.bytes > options.flushBytes()) break;
          long remaining = end - System.nanoTime();
          next = remaining > 0 ? incoming.poll(remaining, TimeUnit.NANOSECONDS) : incoming.poll();
          if (next == null) break;
          if (bytes + next.bytes > options.flushBytes()) {
            carry = next;
            break;
          }
          batch.add(next);
          bytes += next.bytes;
        }
        batch.removeIf(
            t -> {
              if (t.result.isDone()) {
                t.durable.completeExceptionally(new ApiError(503, "INGEST_ACCEPTANCE_UNCERTAIN"));
                t.releasePayload();
                return true;
              }
              return false;
            });
        if (batch.isEmpty()) continue;
        boolean persisted = persist(batch);
        for (var ticket : batch) {
          ticket.releasePayload();
          if (persisted) ticket.durable.complete(null);
          else {
            ticket.durable.completeExceptionally(new ApiError(503, "INGEST_QUEUE_FULL"));
            ticket.result.complete(error(503, "INGEST_QUEUE_FULL"));
          }
        }
        if (persisted && wakeup.availablePermits() == 0) wakeup.release();
      } catch (InterruptedException interrupted) {
        for (var ticket : batch) {
          ticket.releasePayload();
          ticket.durable.completeExceptionally(interrupted);
          ticket.result.complete(error(503, "INGEST_SHUTTING_DOWN"));
        }
        Thread.currentThread().interrupt();
        break;
      } catch (RuntimeException failure) {
        log.warn("Durable ingest enqueue failed ({})", failure.getClass().getSimpleName());
        for (var ticket : batch) {
          ticket.releasePayload();
          ticket.durable.completeExceptionally(failure);
          ticket.result.complete(error(503, "INGEST_QUEUE_UNAVAILABLE"));
        }
      }
    }
  }

  boolean persist(List<Ticket> batch) {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              store.db.execute("SET LOCAL synchronous_commit=on");
              int events = batch.stream().mapToInt(t -> t.received).sum();
              long bytes = batch.stream().mapToLong(t -> t.bytes).sum();
              var args = new ArrayList<Object>();
              for (var ticket : batch)
                Collections.addAll(
                    args,
                    ticket.id,
                    ticket.scope.workspace(),
                    ticket.scope.project(),
                    ticket.scope.hash(),
                    ticket.payload,
                    ticket.received,
                    ticket.bytes);
              store.db.update(
                  "INSERT INTO"
                      + " ingest_accept_queue(id,workspace_id,project_id,key_hash,payload,received,payload_bytes)"
                      + " VALUES "
                      + String.join(
                          ",", Collections.nCopies(batch.size(), "(?,?,?,?,?::jsonb,?,?)")),
                  args.toArray());
              // Acquire FK key-share locks before the global storage budget lock.
              int allowed =
                  store.db.update(
                      "UPDATE ingest_queue_budget SET"
                          + " requests=requests+?,events=events+?,bytes=bytes+? WHERE id=1 AND"
                          + " requests+?<=? AND events+?<=? AND bytes+?<=?",
                      batch.size(),
                      events,
                      bytes,
                      batch.size(),
                      options.maxQueuedRequests(),
                      events,
                      options.maxQueuedEvents(),
                      bytes,
                      options.maxQueuedBytes());
              if (allowed == 0) {
                status.setRollbackOnly();
                return false;
              }
              metrics.counter("metertrail_ingest_queue_write_batches").increment();
              metrics.summary("metertrail_ingest_queue_write_requests").record(batch.size());
              return true;
            }));
  }

  private void consumer() {
    while (running) {
      try {
        if (!processOne()) {
          wakeup.drainPermits();
          wakeup.tryAcquire(20, TimeUnit.MILLISECONDS);
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      } catch (RuntimeException failure) {
        log.warn("Durable ingest processing retry ({})", failure.getClass().getSimpleName());
        try {
          Thread.sleep(100);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }

  boolean processOne() {
    var claimed = new ArrayList<Map<String, Object>>();
    try {
      Finished finished =
          tx.execute(
              status -> {
                store.db.execute("SET LOCAL synchronous_commit=on");
                var heads =
                    store.db.queryForList(
                        "SELECT workspace_id,project_id,min(created_at) AS first_at FROM"
                            + " ingest_accept_queue WHERE state='queued' AND available_at<=now()"
                            + " GROUP BY workspace_id,project_id ORDER BY min(created_at) LIMIT 8");
                UUID workspace = null, project = null;
                for (var head : heads) {
                  UUID candidate = (UUID) head.get("workspace_id");
                  if (Boolean.TRUE.equals(
                      store.db.queryForObject(
                          "SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",
                          Boolean.class,
                          "metertrail-ingest:" + candidate))) {
                    workspace = candidate;
                    project = (UUID) head.get("project_id");
                    break;
                  }
                }
                if (workspace == null) return null;
                var rows =
                    store.db.queryForList(
                        "SELECT id,received,payload_bytes,attempts FROM ingest_accept_queue WHERE"
                            + " workspace_id=? AND project_id=? AND state='queued' AND"
                            + " available_at<=now() ORDER BY created_at,id FOR UPDATE SKIP LOCKED"
                            + " LIMIT ?",
                        workspace,
                        project,
                        options.writeBatchRequests());
                int count = 0, bytes = 0;
                for (var row : rows) {
                  int n = ((Number) row.get("received")).intValue(),
                      b = ((Number) row.get("payload_bytes")).intValue();
                  if (!claimed.isEmpty()
                      && (count + n > options.groupEvents() || bytes + b > options.flushBytes()))
                    break;
                  claimed.add(row);
                  count += n;
                  bytes += b;
                  if (((Number) row.get("attempts")).intValue() > 0) break;
                }
                if (claimed.isEmpty()) return null;
                // Bound payload reads too, not just the eventual aggregation. Candidate
                // metadata may describe hundreds of large envelopes beyond this group.
                Object[] selected = claimed.stream().map(r -> r.get("id")).toArray();
                var payloads =
                    store.db.queryForList(
                        "SELECT * FROM ingest_accept_queue WHERE id IN ("
                            + String.join(",", Collections.nCopies(selected.length, "?"))
                            + ") ORDER BY created_at,id",
                        selected);
                if (payloads.size() != selected.length)
                  throw new IllegalStateException("Missing claimed envelope");
                claimed.clear();
                claimed.addAll(payloads);
                var hashes =
                    claimed.stream().map(r -> (String) r.get("key_hash")).distinct().toList();
                var authArgs = new ArrayList<Object>();
                authArgs.add(project);
                authArgs.add(workspace);
                authArgs.addAll(hashes);
                var active =
                    new HashSet<>(
                        store.db.queryForList(
                            "SELECT k.key_hash FROM api_keys k JOIN projects p ON p.id=k.project_id"
                                + " WHERE p.id=? AND p.workspace_id=? AND p.deleted_at IS NULL AND"
                                + " k.revoked_at IS NULL AND k.key_hash IN ("
                                + String.join(",", Collections.nCopies(hashes.size(), "?"))
                                + ")",
                            String.class,
                            authArgs.toArray()));
                var valid = new ArrayList<AggregatedIngest.Request>();
                var results = new LinkedHashMap<UUID, AggregatedIngest.Result>();
                for (var row : claimed) {
                  UUID id = (UUID) row.get("id");
                  if (active.contains(row.get("key_hash")))
                    valid.add(
                        new AggregatedIngest.Request(
                            id, Support.parse(row.get("payload").toString())));
                  else results.put(id, error(401, "INVALID_API_KEY"));
                }
                UUID block = null;
                if (!valid.isEmpty()) {
                  var accepted = aggregate.accept(workspace, project, valid);
                  block = accepted.block();
                  results.putAll(accepted.results());
                }
                finish(claimed, results, block);
                return new Finished(block, results);
              });
      if (finished == null) return false;
      complete(finished.results());
      return true;
    } catch (RuntimeException failure) {
      if (!claimed.isEmpty()) retry(claimed, failure);
      throw failure;
    }
  }

  private void finish(
      List<Map<String, Object>> rows, Map<UUID, AggregatedIngest.Result> results, UUID block) {
    var args = new ArrayList<Object>();
    for (var row : rows) {
      UUID id = (UUID) row.get("id");
      var result = results.get(id);
      if (result == null) throw new IllegalStateException("Missing acceptance receipt");
      Collections.addAll(args, id, result.status(), JSON.valueToTree(result.body()).toString());
    }
    args.add(block);
    store.db.update(
        "UPDATE ingest_accept_queue q SET state=CASE WHEN v.status=200 THEN 'accepted' ELSE"
            + " 'rejected' END,"
            + " http_status=v.status,response=v.body,payload=NULL,completed_at=now(),quota_block_id=?"
            + " FROM (VALUES "
            + String.join(",", Collections.nCopies(rows.size(), "(?::uuid,?::integer,?::jsonb)"))
            + ") v(id,status,body) WHERE q.id=v.id",
        reorderBlock(args, block));
    releaseBudget(rows);
  }

  private Object[] reorderBlock(List<Object> args, UUID block) {
    // SET placeholders precede VALUES placeholders.
    args.remove(args.size() - 1);
    args.add(0, block);
    return args.toArray();
  }

  private void releaseBudget(List<Map<String, Object>> rows) {
    long events = rows.stream().mapToLong(r -> ((Number) r.get("received")).longValue()).sum();
    long bytes = rows.stream().mapToLong(r -> ((Number) r.get("payload_bytes")).longValue()).sum();
    store.db.update(
        "UPDATE ingest_queue_budget SET requests=requests-?,events=events-?,bytes=bytes-? WHERE"
            + " id=1",
        rows.size(),
        events,
        bytes);
  }

  private void retry(List<Map<String, Object>> rows, RuntimeException failure) {
    var completed =
        tx.execute(
            status -> {
              var done = new LinkedHashMap<UUID, AggregatedIngest.Result>();
              for (var row : rows) {
                UUID id = (UUID) row.get("id");
                var updated =
                    store.db.queryForList(
                        "UPDATE ingest_accept_queue SET attempts=attempts+1,last_error=?,"
                            + " available_at=now()+interval '1 second' WHERE id=? AND"
                            + " state='queued' RETURNING *",
                        failure.getClass().getSimpleName(),
                        id);
                if (updated.isEmpty())
                  continue; // Unknown commit may already have completed this receipt.
                if (((Number) updated.get(0).get("attempts")).intValue() >= options.maxAttempts()) {
                  var result = error(503, "INGEST_PROCESSING_FAILED");
                  // Retain poison input and its storage budget until an owner replays or cancels
                  // it.
                  store.db.update(
                      "UPDATE ingest_accept_queue SET"
                          + " state='quarantined',http_status=503,response=?::jsonb,completed_at=now()"
                          + " WHERE id=?",
                      JSON.valueToTree(result.body()).toString(),
                      id);
                  done.put(id, result);
                }
              }
              return done;
            });
    if (completed != null) complete(completed);
  }

  private void complete(Map<UUID, AggregatedIngest.Result> results) {
    results.forEach(
        (id, result) -> {
          var ticket = waiting.get(id);
          if (ticket != null) ticket.result.complete(result);
        });
  }

  void pollReceipts() {
    var ids = new ArrayList<>(waiting.keySet());
    if (ids.isEmpty()) return;
    var rows =
        store.read.queryForList(
            "SELECT id,http_status,response FROM ingest_accept_queue WHERE state<>'queued' AND id"
                + " IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ")",
            ids.toArray());
    for (var row : rows) {
      Map<String, Object> body =
          JSON.convertValue(
              Support.parse(row.get("response").toString()),
              new TypeReference<Map<String, Object>>() {});
      complete(
          Map.of(
              (UUID) row.get("id"),
              new AggregatedIngest.Result(((Number) row.get("http_status")).intValue(), body)));
    }
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 60000)
  void clean() {
    if (!running || !options.acceptorEnabled()) return;
    store.db.update(
        "DELETE FROM ingest_accept_queue WHERE id IN (SELECT id FROM ingest_accept_queue WHERE"
            + " state IN ('accepted','rejected') AND completed_at<now()-make_interval(secs=>?)"
            + " ORDER BY completed_at LIMIT 5000)",
        options.terminalRetentionSeconds());
  }

  @Scheduled(fixedDelay = 5000)
  void collect() {
    if (!running) return;
    var budget =
        store.read.queryForMap("SELECT requests,events,bytes FROM ingest_queue_budget WHERE id=1");
    queuedRequests.set(((Number) budget.get("requests")).longValue());
    queuedEvents.set(((Number) budget.get("events")).longValue());
    queuedBytes.set(((Number) budget.get("bytes")).longValue());
  }

  @Override
  public void start() {
    if (!options.enabled() || running) return;
    if (!"on".equals(store.db.queryForObject("SHOW fsync", String.class)))
      throw new IllegalStateException("Durable ingestion requires PostgreSQL fsync=on");
    running = true;
    var number = new AtomicInteger();
    threads =
        Executors.newFixedThreadPool(
            options.acceptorEnabled() ? 3 : 2,
            r -> new Thread(r, "ingest-pipeline-" + number.incrementAndGet()));
    threads.submit(this::writer);
    if (options.acceptorEnabled()) threads.submit(this::consumer);
    threads.submit(
        () -> {
          while (running) {
            try {
              pollReceipts();
              Thread.sleep(options.receiptPollMillis());
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              break;
            } catch (RuntimeException failure) {
              metrics.counter("metertrail_ingest_receipt_poll_errors").increment();
              try {
                Thread.sleep(100);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
              }
            }
          }
        });
  }

  @Override
  public void stop() {
    running = false;
    wakeup.release();
    if (threads != null) {
      threads.shutdown();
      try {
        if (!threads.awaitTermination(20, TimeUnit.SECONDS)) threads.shutdownNow();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        threads.shutdownNow();
      }
    }
    waiting.values().forEach(t -> t.result.complete(error(503, "INGEST_SHUTTING_DOWN")));
    Ticket abandoned;
    while ((abandoned = incoming.poll()) != null) {
      abandoned.releasePayload();
      abandoned.durable.completeExceptionally(new ApiError(503, "INGEST_SHUTTING_DOWN"));
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return 0;
  }
}
