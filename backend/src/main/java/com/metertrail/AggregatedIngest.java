package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Preserve client batch boundaries while coalescing authoritative acceptance into one transaction.
 */
@Service
class AggregatedIngest {
  record Request(UUID id, JsonNode body) {}

  record Result(int status, Map<String, Object> body) {}

  record Accepted(UUID block, Map<UUID, Result> results) {}

  final IngestService ingest;
  final Store store;
  final QuotaBlocks blocks;
  final MeterRegistry metrics;
  final TransactionTemplate nested;

  AggregatedIngest(
      IngestService ingest,
      Store store,
      QuotaBlocks blocks,
      MeterRegistry metrics,
      PlatformTransactionManager transactions) {
    this.ingest = ingest;
    this.store = store;
    this.blocks = blocks;
    this.metrics = metrics;
    nested = new TransactionTemplate(transactions);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  }

  Accepted accept(UUID workspace, UUID project, List<Request> requests) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Aggregation requires a write transaction");
    var prepared = new ArrayList<IngestService.PreparedEvent>();
    var combined = JsonNodeFactory.instance.objectNode();
    combined.put("schemaVersion", "1.0");
    combined.set("agent", requests.get(0).body.path("agent"));
    var events = combined.putArray("events");
    for (var request : requests) {
      int index = 0;
      for (var event : ingest.prepare(request.body)) prepared.add(event.route(request.id, index++));
      request.body.path("events").forEach(events::add);
    }
    long locking = System.nanoTime();
    store.db.queryForObject(
        "SELECT id FROM workspaces WHERE id=? FOR NO KEY UPDATE", UUID.class, workspace);
    metrics
        .timer("metertrail_ingest_phase", "phase", "workspace_lock_acquire")
        .record(System.nanoTime() - locking, java.util.concurrent.TimeUnit.NANOSECONDS);
    long locked = System.nanoTime();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            metrics
                .timer("metertrail_ingest_phase", "phase", "workspace_lock_held")
                .record(System.nanoTime() - locked, java.util.concurrent.TimeUnit.NANOSECONDS);
          }
        });
    UUID block = blocks.reserve(workspace, project, prepared.size());
    var clientBatches = new ArrayList<Object>();
    for (var request : requests)
      Collections.addAll(
          clientBatches,
          request.id,
          project,
          request.body.path("agent").toString(),
          request.body.path("events").size());
    store.db.update(
        "INSERT INTO ingest_batches(id,project_id,agent,received) VALUES "
            + String.join(",", Collections.nCopies(requests.size(), "(?,?,?::jsonb,?)")),
        clientBatches.toArray());
    Map<UUID, Result> results;
    var outcomes = new ArrayList<IngestService.EventOutcome>();
    try {
      var receipt =
          nested.execute(tx -> ingest.acceptPrepared(project, combined, prepared, true, outcomes));
      results = split(project, requests, outcomes, (UUID) receipt.get("batchId"));
    } catch (ApiError edge) {
      // All-rejected mega-batches must still preserve each original request's HTTP semantics.
      store.db.update(
          "DELETE FROM ingest_batches WHERE id IN ("
              + String.join(",", Collections.nCopies(requests.size(), "?"))
              + ")",
          requests.stream().map(Request::id).toArray());
      results = new LinkedHashMap<>();
      for (var request : requests) {
        try {
          var body =
              nested.execute(
                  tx ->
                      ingest.acceptPrepared(
                          project, request.body, ingest.prepare(request.body), true, null));
          results.put(request.id, new Result(200, body));
        } catch (ApiError rejected) {
          results.put(request.id, new Result(rejected.status, Map.of("code", rejected.code)));
        }
      }
    }
    blocks.settle(block, workspace);
    metrics.summary("metertrail_ingest_group_requests").record(requests.size());
    metrics.summary("metertrail_ingest_group_events").record(prepared.size());
    metrics.counter("metertrail_ingest_groups").increment();
    return new Accepted(block, results);
  }

  private Map<UUID, Result> split(
      UUID project,
      List<Request> requests,
      List<IngestService.EventOutcome> outcomes,
      UUID temporary) {
    int expected = requests.stream().mapToInt(r -> r.body.path("events").size()).sum();
    if (outcomes.size() != expected)
      throw new IllegalStateException("Incomplete aggregation outcomes");
    var result = new LinkedHashMap<UUID, Result>();
    var batchArgs = new ArrayList<Object>();
    var quotaRejected = new ArrayList<Object>();
    int global = 0, batchCount = 0;
    for (var request : requests) {
      int accepted = 0, duplicates = 0, rejected = 0, index = 0;
      var errors = new ArrayList<Map<String, Object>>();
      boolean quota = false;
      for (JsonNode ignored : request.body.path("events")) {
        var outcome = outcomes.get(global);
        if (outcome.index() != global)
          throw new IllegalStateException("Unordered aggregation outcomes");
        switch (outcome.status()) {
          case "accepted" -> accepted++;
          case "duplicate" -> duplicates++;
          default -> rejected++;
        }
        if (outcome.code() != null) errors.add(Map.of("index", index, "code", outcome.code()));
        quota |= "QUOTA_EXCEEDED".equals(outcome.code());
        global++;
        index++;
      }
      if (accepted == 0 && duplicates == 0 && quota) {
        result.put(request.id, new Result(429, Map.of("code", "QUOTA_EXCEEDED")));
        quotaRejected.add(request.id);
      } else {
        String errorJson =
            new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(errors).toString();
        Collections.addAll(batchArgs, request.id, accepted, duplicates, rejected, errorJson);
        batchCount++;
        result.put(
            request.id,
            new Result(200, Map.of("batchId", request.id, "received", index, "status", "pending")));
      }
    }
    if (batchCount > 0)
      store.db.update(
          "UPDATE ingest_batches b SET"
              + " accepted=v.accepted,duplicates=v.duplicates,rejected=v.rejected,errors=v.errors"
              + " FROM (VALUES "
              + String.join(
                  ",",
                  Collections.nCopies(
                      batchCount, "(?::uuid,?::integer,?::integer,?::integer,?::jsonb)"))
              + ") v(id,accepted,duplicates,rejected,errors) WHERE b.id=v.id",
          batchArgs.toArray());
    if (!quotaRejected.isEmpty())
      store.db.update(
          "DELETE FROM ingest_batches WHERE id IN ("
              + String.join(",", Collections.nCopies(quotaRejected.size(), "?"))
              + ")",
          quotaRejected.toArray());
    store.db.update("DELETE FROM ingest_batches WHERE id=?", temporary);
    return result;
  }
}
