package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "metertrail.secret=test-secret-at-least-32-characters-long",
      "metertrail.worker-enabled=false",
      "metertrail.ingest.pipeline.enabled=true",
      "metertrail.ingest.pipeline.acceptor-enabled=false",
      "metertrail.usage-collection-enabled=true",
      "metertrail.secure-cookies=false"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DurableIngestIntegrationTest {
  @Container
  static PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withCommand("postgres", "-c", "fsync=on");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", pg::getJdbcUrl);
    r.add("spring.datasource.username", pg::getUsername);
    r.add("spring.datasource.password", pg::getPassword);
  }

  @AfterAll
  static void stop(@Autowired org.springframework.context.ConfigurableApplicationContext context) {
    context.getBean(IngestPipeline.class).stop();
    context.getBean(TraceCountCache.class).destroy();
    context
        .getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
        .values()
        .forEach(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler::destroy);
  }

  @Autowired Store store;
  @Autowired IngestPipeline pipeline;
  @Autowired QuotaBlocks blocks;
  @Autowired MeterRegistry metrics;
  @Autowired PlatformTransactionManager transactions;
  @Autowired MockMvc mvc;
  @Autowired ConsoleController console;
  @Autowired TraceCountCache traceCounts;

  @Autowired
  @Qualifier("writeDataSource")
  HikariDataSource writes;

  @Autowired
  @Qualifier("readDataSource")
  HikariDataSource reads;

  @MockBean RateLimits limits;
  UUID user, workspace, project;
  String key, session;
  Store.KeyScope scope;

  @BeforeEach
  void fixture() {
    user = UUID.randomUUID();
    workspace = UUID.randomUUID();
    project = UUID.randomUUID();
    key = "lm_live_" + Support.token();
    session = Support.token();
    store.db.update(
        "INSERT INTO users(id,email,verified) VALUES(?,?,true)", user, user + "@test.local");
    store.db.update(
        "INSERT INTO workspaces(id,owner_id,name) VALUES(?,?,'durable-test')", workspace, user);
    store.db.update(
        "INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'durable-test')",
        project,
        workspace);
    store.db.update(
        "INSERT INTO api_keys(id,project_id,name,prefix,key_hash)"
            + " VALUES(?,?,'test','lm_live_test',?)",
        UUID.randomUUID(),
        project,
        Support.hash(key));
    store.db.update(
        "INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1"
            + " hour')",
        Support.hash(session),
        user,
        "test-csrf");
    scope = store.keyScope("Bearer " + key);
  }

  @AfterEach
  void drain() throws Exception {
    if (!pipeline.isRunning()) pipeline.start();
    for (var ticket : List.copyOf(pipeline.waiting.values())) {
      try {
        ticket.durable.get(10, TimeUnit.SECONDS);
      } catch (ExecutionException expected) {
      }
    }
    store.db.update("UPDATE ingest_accept_queue SET available_at=now() WHERE state='queued'");
    for (int i = 0; i < 20 && pipeline.processOne(); i++) {}
    assertBudget();
  }

  ObjectNode event(String name, int revision) {
    return new ObjectMapper()
        .valueToTree(
            Map.of(
                "eventId",
                UUID.randomUUID(),
                "type",
                "trace",
                "objectId",
                name,
                "revision",
                revision,
                "traceId",
                "0123456789abcdef0123456789abcdef",
                "startedAt",
                Instant.now().toString(),
                "data",
                Map.of("name", name, "status", "success", "durationMs", 10)));
  }

  ObjectNode body(JsonNode... events) {
    var b = new ObjectMapper().createObjectNode();
    b.put("schemaVersion", "1.0");
    b.set(
        "agent",
        new ObjectMapper()
            .valueToTree(
                Map.of("serviceName", "durable-test", "environment", "test", "instanceId", "one")));
    var a = b.putArray("events");
    for (var e : events) a.add(e);
    return b;
  }

  IngestPipeline.Ticket enqueue(JsonNode body) throws Exception {
    var t = pipeline.submit(scope, body);
    t.durable.get(10, TimeUnit.SECONDS);
    return t;
  }

  AggregatedIngest.Result result(IngestPipeline.Ticket ticket) throws Exception {
    return ticket.result.get(10, TimeUnit.SECONDS);
  }

  Map<String, Object> batch(IngestPipeline.Ticket t) throws Exception {
    var receipt = result(t);
    assertEquals(200, receipt.status());
    return store.db.queryForMap(
        "SELECT * FROM ingest_batches WHERE id=?",
        Support.uuid(receipt.body().get("batchId").toString()));
  }

  long units() {
    return store.db.queryForObject(
        "SELECT used_units FROM workspaces WHERE id=?", Long.class, workspace);
  }

  void assertBudget() {
    var b = store.db.queryForMap("SELECT * FROM ingest_queue_budget WHERE id=1");
    var q =
        store.db.queryForMap(
            "SELECT count(*) AS requests,coalesce(sum(received),0) AS"
                + " events,coalesce(sum(payload_bytes),0) AS bytes FROM ingest_accept_queue WHERE"
                + " state IN ('queued','quarantined')");
    for (String field : List.of("requests", "events", "bytes"))
      assertEquals(((Number) q.get(field)).longValue(), ((Number) b.get(field)).longValue(), field);
  }

  @Test
  void indexedTraceCountPreservesOwnershipRetentionAndTypeFilters() {
    store.db.update(
        "INSERT INTO objects(project_id,type,object_id,revision,started_at,data) VALUES"
            + " (?,'trace','count-new',1,now(),'{}'),(?,'trace','count-100d',1,now()-interval '100"
            + " days','{}'), (?,'trace','count-old',1,now()-interval '1200"
            + " days','{}'),(?,'score','count-score',1,now(),'{}')",
        project,
        project,
        project,
        project);
    assertEquals(1, console.countTraces(project, user));
    store.db.update("UPDATE workspaces SET plan='Pro' WHERE id=?", workspace);
    traceCounts.invalidate(project);
    assertEquals(2, console.countTraces(project, user));
    assertEquals(0, console.countTraces(project, UUID.randomUUID()));
    assertEquals(2, console.countTraces(project, user));
    store.db.update("UPDATE projects SET deleted_at=now() WHERE id=?", project);
    traceCounts.invalidate(project);
    assertEquals(0, console.countTraces(project, user));
  }

  @Test
  void temporaryBatchForeignKeyCheckHasAUsableReservationIndex() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              store.db.execute("SET LOCAL enable_seqscan=off");
              String plan =
                  store.db.queryForObject(
                      "EXPLAIN (FORMAT JSON) SELECT 1 FROM ONLY billable_objects WHERE"
                          + " reservation_batch=? FOR KEY SHARE",
                      String.class,
                      UUID.randomUUID());
              assertTrue(plan.contains("billable_reservation_batch"), plan);
            });
  }

  @Test
  void fortyRequestsShareOneQuotaBlockAndKeepTheirClientBatches() throws Exception {
    var tickets = new ArrayList<IngestPipeline.Ticket>();
    for (int i = 0; i < 40; i++)
      tickets.add(pipeline.submit(scope, body(event("coalesced-" + i, 1))));
    for (var t : tickets) t.durable.get(10, TimeUnit.SECONDS);
    long locks =
        metrics.get("metertrail_ingest_phase").tag("phase", "workspace_lock_held").timer().count();
    assertTrue(pipeline.processOne());
    assertEquals(
        locks + 1,
        metrics.get("metertrail_ingest_phase").tag("phase", "workspace_lock_held").timer().count());
    assertEquals(40, units());
    assertEquals(
        1L,
        store.db.queryForObject(
            "SELECT count(*) FROM ingest_quota_blocks WHERE project_id=? AND allocated_units=40 AND"
                + " consumed_units=40 AND state='settled'",
            Long.class,
            project));
    for (var t : tickets) {
      var b = batch(t);
      assertEquals(1, b.get("accepted"));
      assertEquals(1, b.get("received"));
      assertEquals(t.id, b.get("id"));
    }
    assertEquals(
        40L,
        store.db.queryForObject(
            "SELECT count(*) FROM ingest_batches WHERE project_id=?", Long.class, project));
    assertEquals(
        40L,
        store.db.queryForObject(
            "SELECT count(*) FROM usage_ledger WHERE project_id=? AND quota_block_id IS NOT NULL",
            Long.class,
            project));
    assertBudget();
  }

  @Test
  void duplicatesConflictsAndRevisionsPreserveIndicesAndReservationOwner() throws Exception {
    var first = event("object", 1);
    var invalid = event("invalid", 0);
    var a = enqueue(body(first, invalid));
    var b = enqueue(body(event("object", 2)));
    var c = enqueue(body(event("object", 1)));
    var conflict = first.deepCopy();
    conflict.withObject("data").put("name", "conflict");
    var d = enqueue(body(conflict));
    assertTrue(pipeline.processOne());
    assertEquals(1, units());
    var ba = batch(a);
    assertEquals(1, ba.get("accepted"));
    assertEquals(1, ba.get("rejected"));
    JsonNode errors = Support.parse(ba.get("errors").toString());
    assertEquals(1, errors.get(0).path("index").asInt());
    assertEquals("INVALID_REVISION", errors.get(0).path("code").asText());
    assertEquals(1, batch(b).get("accepted"));
    assertEquals(1, batch(c).get("duplicates"));
    assertEquals(
        "STALE_REVISION",
        Support.parse(batch(c).get("errors").toString()).get(0).path("code").asText());
    assertEquals(
        "EVENT_ID_CONFLICT",
        Support.parse(batch(d).get("errors").toString()).get(0).path("code").asText());
    assertEquals(
        a.id,
        store.db.queryForObject(
            "SELECT reservation_batch FROM billable_objects WHERE project_id=?",
            UUID.class,
            project));
    assertEquals(
        0,
        store.db.queryForObject(
            "SELECT event_index FROM ingest_events WHERE batch_id=?", Integer.class, b.id));
  }

  @Test
  void identicalEventAcrossRequestsConsumesExactlyOneUnit() throws Exception {
    var e = event("replayed", 1);
    var a = enqueue(body(e));
    var b = enqueue(body(e));
    pipeline.processOne();
    assertEquals(1, batch(a).get("accepted"));
    assertEquals(1, batch(b).get("duplicates"));
    assertEquals(1, units());
    var retry = enqueue(body(e));
    pipeline.processOne();
    assertEquals(1, batch(retry).get("duplicates"));
    assertEquals(1, units());
    assertEquals(
        0L,
        store.db.queryForObject(
            "SELECT allocated_units FROM ingest_quota_blocks WHERE project_id=? ORDER BY created_at"
                + " DESC LIMIT 1",
            Long.class,
            project));
  }

  @Test
  void quotaBoundaryDoesNotTurnRejectedClientIntoHttp200() throws Exception {
    store.db.update("UPDATE workspaces SET used_units=49999 WHERE id=?", workspace);
    var a = enqueue(body(event("last-unit", 1)));
    var b = enqueue(body(event("over-limit", 1)));
    pipeline.processOne();
    assertEquals(200, result(a).status());
    assertEquals(429, result(b).status());
    assertEquals("QUOTA_EXCEEDED", result(b).body().get("code"));
    assertEquals(50000, units());
    assertEquals(
        1L,
        store.db.queryForObject(
            "SELECT count(*) FROM ingest_batches WHERE project_id=?", Long.class, project));
    assertEquals(
        1L,
        store.db.queryForObject(
            "SELECT allocated_units FROM ingest_quota_blocks WHERE project_id=?",
            Long.class,
            project));
  }

  @Test
  void allRejectedAggregationPreservesPerRequestInvalidAndQuotaSemantics() throws Exception {
    store.db.update("UPDATE workspaces SET used_units=50000 WHERE id=?", workspace);
    var a = enqueue(body(event("invalid-only", 0)));
    var b = enqueue(body(event("quota-only", 1)));
    pipeline.processOne();
    assertEquals(200, result(a).status());
    assertEquals(1, batch(a).get("rejected"));
    assertEquals(429, result(b).status());
    assertEquals(50000, units());
    assertEquals(
        0L,
        store.db.queryForObject(
            "SELECT allocated_units FROM ingest_quota_blocks WHERE project_id=?",
            Long.class,
            project));
  }

  @Test
  void revokedKeyAndCanceledSubscriptionAreCheckedAfterPersistence() throws Exception {
    var revoked = enqueue(body(event("revoked", 1)));
    store.db.update("UPDATE api_keys SET revoked_at=now() WHERE project_id=?", project);
    pipeline.processOne();
    assertEquals(401, result(revoked).status());
    assertEquals(0, units());
    store.db.update("UPDATE api_keys SET revoked_at=NULL WHERE project_id=?", project);
    store.db.update(
        "UPDATE workspaces SET plan='Core',subscription_status='canceled' WHERE id=?", workspace);
    var canceled = enqueue(body(event("canceled", 1)));
    pipeline.processOne();
    assertEquals(429, result(canceled).status());
    assertEquals(0, units());
  }

  @Test
  void addonAndConsentedOverageUseExactBlockProvenance() throws Exception {
    store.db.update(
        "UPDATE workspaces SET"
            + " plan='Core',subscription_status='active',subscription_id='synthetic',used_units=100000,addon_units=1"
            + " WHERE id=?",
        workspace);
    UUID pack = UUID.randomUUID();
    store.db.update(
        "INSERT INTO"
            + " usage_charges(id,workspace_id,subscription_id,period_start,period_end,kind,units,amount,status,credited)"
            + " SELECT ?,id,subscription_id,period_start,period_end,'addon',1,8,'paid',true FROM"
            + " workspaces WHERE id=?",
        pack,
        workspace);
    var a = enqueue(body(event("addon-unit", 1)));
    var b = enqueue(body(event("without-consent", 1)));
    pipeline.processOne();
    assertEquals(200, result(a).status());
    assertEquals(429, result(b).status());
    assertEquals(
        pack,
        store.db.queryForObject(
            "SELECT addon_charge_id FROM usage_ledger WHERE project_id=?", UUID.class, project));
    store.db.update(
        "UPDATE workspaces SET auto_overage=true,overage_cap=0.00008 WHERE id=?", workspace);
    var c = enqueue(body(event("authorized-overage", 1)));
    var d = enqueue(body(event("cap-exceeded", 1)));
    pipeline.processOne();
    assertEquals(200, result(c).status());
    assertEquals(429, result(d).status());
    assertEquals(
        1L,
        store.db.queryForObject(
            "SELECT overage_units FROM workspaces WHERE id=?", Long.class, workspace));
    assertEquals(
        2L,
        store.db.queryForObject(
            "SELECT sum(consumed_units) FROM ingest_quota_blocks WHERE project_id=?",
            Long.class,
            project));
  }

  @Test
  void blockCannotCommitUnsettledAndFailureLeavesNoReservation() {
    long before = store.db.queryForObject("SELECT count(*) FROM ingest_quota_blocks", Long.class);
    assertThrows(
        RuntimeException.class,
        () ->
            new TransactionTemplate(transactions)
                .execute(s -> blocks.reserve(workspace, project, 10)));
    assertEquals(
        before, store.db.queryForObject("SELECT count(*) FROM ingest_quota_blocks", Long.class));
    assertEquals(0, units());
  }

  @Test
  void durableRequestsRecoverAfterApiStopAndRetriesCannotRebill() throws Exception {
    var payload = body(event("restart", 1));
    var a = enqueue(payload);
    assertEquals(
        "queued",
        store.db.queryForObject(
            "SELECT state FROM ingest_accept_queue WHERE id=?", String.class, a.id));
    pipeline.stop();
    assertEquals(503, result(a).status());
    assertEquals(0, units());
    pipeline.start();
    assertTrue(pipeline.processOne());
    assertEquals(1, units());
    var retry = enqueue(payload);
    pipeline.processOne();
    assertEquals(1, batch(retry).get("duplicates"));
    assertEquals(1, units());
  }

  @Test
  void forciblyKilledProducerJvmLeavesRecoverableQueueAndNoQuotaLeak() throws Exception {
    var payload = body(event("producer-hard-kill", 1));
    var builder =
        new ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            DurableQueueProcessDrill.class.getName());
    builder.environment().put("DRILL_JDBC_URL", pg.getJdbcUrl());
    builder.environment().put("DRILL_DB_USER", pg.getUsername());
    builder.environment().put("DRILL_DB_PASSWORD", pg.getPassword());
    builder.environment().put("DRILL_API_KEY", key);
    builder.environment().put("DRILL_PAYLOAD", payload.toString());
    var output =
        java.nio.file.Files.createTempFile(
            java.nio.file.Path.of("target"), "durable-producer-", ".log");
    builder.redirectErrorStream(true).redirectOutput(output.toFile());
    var process = builder.start();
    boolean durable = false;
    try {
      for (int i = 0; i < 400; i++) {
        if (java.nio.file.Files.readString(output).contains("QUEUE_DURABLE ")) {
          durable = true;
          break;
        }
        if (!process.isAlive()) break;
        Thread.sleep(100);
      }
      assertTrue(
          durable,
          "Producer did not reach durable commit: " + java.nio.file.Files.readString(output));
      assertEquals(0, units());
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
    assertTrue(pipeline.processOne());
    assertEquals(1, units());
    var retry = enqueue(payload);
    pipeline.processOne();
    assertEquals(1, batch(retry).get("duplicates"));
    assertEquals(1, units());
    assertEquals(
        0L,
        store.db.queryForObject(
            "SELECT count(*) FROM ingest_quota_blocks WHERE project_id=? AND state<>'settled'",
            Long.class,
            project));
  }

  @Test
  void workerMaterializesOriginalClientBatchesInOneBoundedTransaction() throws Exception {
    new Worker(store, new TransactionTemplate(transactions), null, true, "unused@example.test")
        .drain(10000, Long.MAX_VALUE);
    var tickets = new ArrayList<IngestPipeline.Ticket>();
    for (int i = 0; i < 40; i++) tickets.add(enqueue(body(event("worker-group-" + i, 1))));
    assertTrue(pipeline.processOne());
    var commits = new java.util.concurrent.atomic.AtomicInteger();
    var grouped =
        new TransactionTemplate(transactions) {
          @Override
          public <T> T execute(
              org.springframework.transaction.support.TransactionCallback<T> action) {
            commits.incrementAndGet();
            return super.execute(action);
          }
        };
    assertTrue(
        new Worker(store, grouped, null, true, "unused@example.test").drain(40, Long.MAX_VALUE));
    assertEquals(1, commits.get());
    for (var ticket : tickets) assertEquals("completed", batch(ticket).get("status"));
    assertEquals(
        40,
        store.db.queryForObject(
            "SELECT count(*) FROM objects WHERE project_id=?", Integer.class, project));
    assertEquals(40, units());
  }

  @Test
  void workerGroupsRevisionsAndIsolatesFreshPoisonWithoutDelayingHealthyBatches() throws Exception {
    new Worker(store, new TransactionTemplate(transactions), null, true, "unused@example.test")
        .drain(10000, Long.MAX_VALUE);
    var a = enqueue(body(event("worker-revision", 1)));
    var b = enqueue(body(event("worker-revision", 2)));
    assertTrue(pipeline.processOne());
    var worker =
        new Worker(store, new TransactionTemplate(transactions), null, true, "unused@example.test");
    worker.process();
    assertEquals("completed", batch(a).get("status"));
    assertEquals("completed", batch(b).get("status"));
    assertEquals(
        2L,
        store.db.queryForObject(
            "SELECT revision FROM objects WHERE project_id=? AND object_id='worker-revision'",
            Long.class,
            project));
    var bad = enqueue(body(event("worker-fresh-poison", 1)));
    var good = enqueue(body(event("worker-fresh-healthy", 1)));
    assertTrue(pipeline.processOne());
    store.db.update(
        "UPDATE ingest_events SET payload=jsonb_set(payload,'{startedAt}','\"invalid\"') WHERE"
            + " batch_id=?",
        bad.id);
    worker.process();
    assertEquals("pending", batch(bad).get("status"));
    assertEquals("completed", batch(good).get("status"));
    assertEquals(2, ((Number) batch(bad).get("attempts")).intValue());
    assertEquals(3, units());
    // Repair the deliberately damaged downstream payload so fixture cleanup leaves no poison.
    store.db.update(
        "UPDATE ingest_events SET payload=?::jsonb WHERE batch_id=?",
        event("worker-fresh-poison", 1).toString(),
        bad.id);
    store.db.update("UPDATE ingest_batches SET lease_until=now() WHERE id=?", bad.id);
    worker.process();
    assertEquals("completed", batch(bad).get("status"));
    assertEquals(3, units());
  }

  @Test
  void blockedQueueForeignKeyNeverHoldsTheStorageBudgetLock() throws Exception {
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var transaction = new TransactionTemplate(transactions);
    var holding =
        CompletableFuture.runAsync(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      store.db.queryForObject(
                          "SELECT id FROM workspaces WHERE id=? FOR UPDATE", UUID.class, workspace);
                      locked.countDown();
                      try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                      } catch (InterruptedException failure) {
                        throw new RuntimeException(failure);
                      }
                    }));
    assertTrue(locked.await(5, TimeUnit.SECONDS));
    IngestPipeline.Ticket ticket;
    var payload = body(event("blocked-foreign-key", 1));
    try {
      ticket = pipeline.submit(scope, payload);
      boolean blocked = false;
      for (int i = 0; i < 100; i++) {
        blocked =
            store.read.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND"
                    + " wait_event_type='Lock' AND query LIKE 'INSERT INTO ingest_accept_queue%')",
                Boolean.class);
        if (blocked) break;
        Thread.sleep(20);
      }
      assertTrue(blocked, "Producer must be waiting for the workspace FK lock");
      assertEquals(ticket.bytes, pipeline.bufferedBytes.get());
      ticket.uncertain();
      assertEquals(
          ticket.bytes,
          pipeline.bufferedBytes.get(),
          "HTTP timeout must not release memory still owned by the queue writer");
      transaction.executeWithoutResult(
          status -> {
            store.db.execute("SET LOCAL lock_timeout='200ms'");
            assertEquals(
                1, store.db.update("UPDATE ingest_queue_budget SET requests=requests WHERE id=1"));
          });
    } finally {
      release.countDown();
      holding.get(5, TimeUnit.SECONDS);
    }
    ticket.durable.get(5, TimeUnit.SECONDS);
    assertEquals(0, pipeline.bufferedBytes.get());
    assertTrue(pipeline.processOne());
    assertEquals(503, result(ticket).status());
    assertEquals(
        "accepted",
        store.db.queryForObject(
            "SELECT state FROM ingest_accept_queue WHERE id=?", String.class, ticket.id));
    assertEquals(1, units());
    var retry = enqueue(payload);
    assertTrue(pipeline.processOne());
    assertEquals(200, result(retry).status());
    assertEquals(1, batch(retry).get("duplicates"));
    assertEquals(1, units());
  }

  @Test
  void disconnectDuringAllocationRollsBackLedgerAndRecoversQueue() throws Exception {
    var a = enqueue(body(event("disconnect", 1)));
    String function = "ingest_fault_" + UUID.randomUUID().toString().replace("-", "");
    store.db.execute(
        "CREATE FUNCTION "
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"
            + project
            + "'::uuid THEN PERFORM pg_sleep(30); END IF; RETURN NEW; END $$");
    store.db.execute(
        "CREATE TRIGGER "
            + function
            + " BEFORE INSERT ON usage_ledger FOR EACH ROW EXECUTE FUNCTION "
            + function
            + "()");
    var processing = CompletableFuture.runAsync(pipeline::processOne);
    try {
      Integer pid = null;
      for (int i = 0; i < 100; i++) {
        var pids =
            store.read.queryForList(
                "SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND query LIKE"
                    + " 'INSERT INTO usage_ledger%' AND wait_event='PgSleep'",
                Integer.class);
        if (!pids.isEmpty()) {
          pid = pids.get(0);
          break;
        }
        Thread.sleep(50);
      }
      assertNotNull(pid, "Acceptance must reach ledger write after quota-block allocation");
      assertTrue(store.db.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pid));
      assertThrows(ExecutionException.class, () -> processing.get(10, TimeUnit.SECONDS));
    } finally {
      store.db.execute("DROP TRIGGER IF EXISTS " + function + " ON usage_ledger");
      store.db.execute("DROP FUNCTION IF EXISTS " + function + "()");
    }
    assertEquals(0, units());
    assertEquals(
        0L,
        store.db.queryForObject(
            "SELECT count(*) FROM ingest_quota_blocks WHERE project_id=?", Long.class, project));
    assertEquals(
        "queued",
        store.db.queryForObject(
            "SELECT state FROM ingest_accept_queue WHERE id=?", String.class, a.id));
    store.db.update("UPDATE ingest_accept_queue SET available_at=now() WHERE id=?", a.id);
    assertTrue(pipeline.processOne());
    assertEquals(200, result(a).status());
    assertEquals(1, units());
    assertEquals(
        1L,
        store.db.queryForObject(
            "SELECT count(*) FROM ingest_quota_blocks WHERE project_id=? AND consumed_units=1",
            Long.class,
            project));
  }

  @Test
  void httpReceiptWaitsForAcceptanceAndIsImmediatelyQueryable() throws Exception {
    var request =
        mvc.perform(
                post("/v1/events/batch")
                    .header("Authorization", "Bearer " + key)
                    .contentType("application/json")
                    .content(body(event("async-http", 1)).toString()))
            .andExpect(request().asyncStarted())
            .andReturn();
    for (int i = 0;
        i < 100
            && store.db.queryForObject(
                    "SELECT count(*) FROM ingest_accept_queue WHERE project_id=?",
                    Long.class,
                    project)
                == 0;
        i++) Thread.sleep(10);
    assertEquals(0, units());
    assertFalse(request.getResponse().isCommitted());
    pipeline.processOne();
    var response =
        mvc.perform(asyncDispatch(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("pending"))
            .andReturn();
    String id = Support.parse(response.getResponse().getContentAsString()).path("batchId").asText();
    mvc.perform(get("/v1/batches/" + id).header("Authorization", "Bearer " + key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(1));
    assertEquals(1, units());
  }

  @Test
  void malformedEventsReturnImmediate400WithoutAcceptingValidNeighbors() throws Exception {
    var shortId = event("short", 1); shortId.put("eventId", "short");
    var badUsage = event("usage", 1);
    badUsage.withObject("data").put("durationMs", -1);
    mvc.perform(post("/v1/events/batch").header("Authorization", "Bearer " + key)
        .contentType("application/json").content(body(shortId, event("valid", 1), badUsage).toString()))
        .andExpect(request().asyncNotStarted())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EVENT_ID_LENGTH_MUST_BE_36"))
        .andExpect(jsonPath("$.errors.length()").value(2))
        .andExpect(jsonPath("$.errors[0].index").value(0))
        .andExpect(jsonPath("$.errors[0].field").value("events[0].eventId"))
        .andExpect(jsonPath("$.errors[1].index").value(2))
        .andExpect(jsonPath("$.errors[1].code").value("INVALID_DURATION"));
    assertEquals(0, units());
    for (String table : List.of("ingest_accept_queue", "ingest_batches", "usage_ledger"))
      assertEquals(0L, store.db.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id=?", Long.class, project));
  }

  @Test
  void readPoolIsPhysicalReadOnlyAndSurvivesWritePoolExhaustion() throws Exception {
    assertNotSame(writes, reads);
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> store.read.update("UPDATE workspaces SET name='forbidden' WHERE id=?", workspace));
    var held = new ArrayList<Connection>();
    try {
      for (int i = 0; i < writes.getMaximumPoolSize(); i++) held.add(writes.getConnection());
      mvc.perform(
              get("/api/projects/" + project + "/traces")
                  .cookie(new jakarta.servlet.http.Cookie("mt_session", session)))
          .andExpect(status().isOk());
      assertEquals(
          0L,
          store.read.queryForObject(
              "SELECT used_units FROM workspaces WHERE id=?", Long.class, workspace));
    } finally {
      for (var c : held) c.close();
    }
  }

  @Test
  void boundedPendingRequestsRejectBeforeQuotaAndRecoverAfterDrain() throws Exception {
    var pending = new ArrayList<IngestPipeline.Ticket>();
    for (int i = 0; i < pipeline.options.maxInflight(); i++)
      pending.add(pipeline.submit(scope, body(event("bounded-" + i, 1))));
    var busy =
        assertThrows(ApiError.class, () -> pipeline.submit(scope, body(event("not-enqueued", 1))));
    assertEquals("INGEST_BUSY", busy.code);
    assertEquals(0, units());
    for (var t : pending) t.durable.get(10, TimeUnit.SECONDS);
    while (pipeline.processOne()) {}
    for (var t : pending) assertEquals(200, result(t).status());
    assertEquals(pending.size(), units());
    assertBudget();
  }

  @Test
  void freePeriodRolloverSettlesTheBlockInTheNewPeriod() throws Exception {
    store.db.update(
        "UPDATE workspaces SET used_units=50000,period_start=now()-interval '2"
            + " months',period_end=now()-interval '1 month' WHERE id=?",
        workspace);
    var a = enqueue(body(event("new-period", 1)));
    pipeline.processOne();
    assertEquals(200, result(a).status());
    assertEquals(1, units());
    assertTrue(
        store.db.queryForObject(
            "SELECT b.period_start=w.period_start FROM ingest_quota_blocks b JOIN workspaces w ON"
                + " w.id=b.workspace_id WHERE b.project_id=?",
            Boolean.class,
            project));
    assertEquals(
        1L,
        store.db.queryForObject(
            "SELECT consumed_units FROM ingest_quota_blocks WHERE project_id=?",
            Long.class,
            project));
  }

  @Test
  void anotherApiInstanceCanCompleteAndPollingRecoversItsReceipt() throws Exception {
    var a = enqueue(body(event("another-instance", 1)));
    var other =
        new IngestPipeline(
            store,
            pipeline.aggregate,
            pipeline.options,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            transactions);
    assertTrue(other.processOne());
    assertEquals(200, result(a).status());
    assertEquals(1, units());
    assertEquals(1, batch(a).get("accepted"));
  }

  @Test
  void byteBoundedGroupsKeepUnselectedReceiptsPendingAndMakeProgress() throws Exception {
    var options =
        new IngestQueueOptions(
            true, false, 8, 1048576, 200, 2, 700, 2, 20, 5000, 20000, 50000, 134217728, 10, 600);
    var other =
        new IngestPipeline(
            store,
            pipeline.aggregate,
            options,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            transactions);
    try {
      other.start();
      var tickets = new ArrayList<IngestPipeline.Ticket>();
      for (int i = 0; i < 4; i++) {
        var ticket = other.submit(scope, body(event("bounded-" + i, 1)));
        ticket.durable.get(5, TimeUnit.SECONDS);
        tickets.add(ticket);
      }
      assertTrue(tickets.stream().allMatch(t -> t.bytes < 700));
      assertTrue(tickets.get(0).bytes + tickets.get(1).bytes > 700);
      assertTrue(other.processOne());
      assertEquals(1, units());
      assertEquals(
          3L,
          store.db.queryForObject(
              "SELECT count(*) FROM ingest_accept_queue WHERE project_id=? AND state='queued'",
              Long.class,
              project));
      assertEquals(1, tickets.stream().filter(t -> t.result.isDone()).count());
      while (other.processOne()) {}
      for (var ticket : tickets) assertEquals(200, result(ticket).status());
      assertEquals(4, units());
      assertBudget();
    } finally {
      other.stop();
    }
  }

  @Test
  void persistentQueueBudgetRejectsWithoutPersistingOrCharging() throws Exception {
    var tiny =
        new IngestQueueOptions(
            true, false, 8, 1048576, 200, 500, 2097152, 2, 20, 5000, 20000, 1, 134217728, 10, 600);
    var other =
        new IngestPipeline(
            store,
            pipeline.aggregate,
            tiny,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            transactions);
    try {
      other.start();
      var a = other.submit(scope, body(event("one", 1), event("two", 1)));
      assertEquals("INGEST_QUEUE_FULL", result(a).body().get("code"));
      assertEquals(503, result(a).status());
      assertEquals(0, units());
      assertEquals(
          0L,
          store.db.queryForObject(
              "SELECT count(*) FROM ingest_accept_queue WHERE project_id=?", Long.class, project));
      assertBudget();
    } finally {
      other.stop();
    }
  }

  @Test
  void concurrentProjectsCannotOverspendTheSharedWorkspaceQuota() throws Exception {
    store.db.update("UPDATE workspaces SET used_units=49999 WHERE id=?", workspace);
    UUID p2 = UUID.randomUUID();
    String k2 = "lm_live_" + Support.token();
    store.db.update(
        "INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'second')", p2, workspace);
    store.db.update(
        "INSERT INTO api_keys(id,project_id,name,prefix,key_hash)"
            + " VALUES(?,?,'second','lm_live_second',?)",
        UUID.randomUUID(),
        p2,
        Support.hash(k2));
    var a = enqueue(body(event("shared-one", 1)));
    var b = pipeline.submit(store.keyScope("Bearer " + k2), body(event("shared-two", 1)));
    b.durable.get(10, TimeUnit.SECONDS);
    CompletableFuture.allOf(
            CompletableFuture.runAsync(pipeline::processOne),
            CompletableFuture.runAsync(pipeline::processOne))
        .get(10, TimeUnit.SECONDS);
    while (pipeline.processOne()) {}
    assertEquals(Set.of(200, 429), Set.of(result(a).status(), result(b).status()));
    assertEquals(50000, units());
  }

  @Test
  void poisonInputIsRetainedBoundedAndOwnerCanReplayAfterTheFix() throws Exception {
    var bad = enqueue(body(event("queue-poison", 1)));
    var good = enqueue(body(event("queue-healthy", 1)));
    String function = "queue_poison_" + UUID.randomUUID().toString().replace("-", "");
    store.db.execute(
        "CREATE FUNCTION "
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"
            + project
            + "'::uuid AND NEW.object_id='queue-poison' THEN RAISE EXCEPTION 'synthetic poison';"
            + " END IF; RETURN NEW; END $$");
    store.db.execute(
        "CREATE TRIGGER "
            + function
            + " BEFORE INSERT ON usage_ledger FOR EACH ROW EXECUTE FUNCTION "
            + function
            + "()");
    try {
      assertThrows(RuntimeException.class, pipeline::processOne);
      // Let the healthy request proceed while poison is in retry backoff.
      store.db.update("UPDATE ingest_accept_queue SET available_at=now() WHERE id=?", good.id);
      assertTrue(pipeline.processOne());
      assertEquals(200, result(good).status());
      for (int i = 1; i < pipeline.options.maxAttempts(); i++) {
        store.db.update("UPDATE ingest_accept_queue SET available_at=now() WHERE id=?", bad.id);
        assertThrows(RuntimeException.class, pipeline::processOne);
      }
      assertEquals(503, result(bad).status());
      assertEquals(1, units());
      assertEquals(
          "quarantined",
          store.db.queryForObject(
              "SELECT state FROM ingest_accept_queue WHERE id=?", String.class, bad.id));
      assertTrue(
          store.db.queryForObject(
              "SELECT payload IS NOT NULL FROM ingest_accept_queue WHERE id=?",
              Boolean.class,
              bad.id));
      assertBudget();
    } finally {
      store.db.execute("DROP TRIGGER IF EXISTS " + function + " ON usage_ledger");
      store.db.execute("DROP FUNCTION IF EXISTS " + function + "()");
    }
    String path = "/api/projects/" + project + "/ingest-queue/" + bad.id + "/replay";
    mvc.perform(
            post(path)
                .cookie(new jakarta.servlet.http.Cookie("mt_session", session))
                .header("X-CSRF-Token", "test-csrf"))
        .andExpect(status().isOk());
    assertTrue(pipeline.processOne());
    assertEquals(2, units());
    assertBudget();
    mvc.perform(
            post(path)
                .cookie(new jakarta.servlet.http.Cookie("mt_session", session))
                .header("X-CSRF-Token", "test-csrf"))
        .andExpect(status().isConflict());
  }

  @Test
  void ownerCancellationReleasesOnlyQuarantinedStorageAndNeverChangesUsage() throws Exception {
    var a = enqueue(body(event("retained-unaccepted", 1)));
    store.db.update(
        "UPDATE ingest_accept_queue SET"
            + " state='quarantined',http_status=503,response='{\"code\":\"INGEST_PROCESSING_FAILED\"}'::jsonb,completed_at=now()"
            + " WHERE id=?",
        a.id);
    String path = "/api/projects/" + project + "/ingest-queue/" + a.id;
    mvc.perform(delete(path).cookie(new jakarta.servlet.http.Cookie("mt_session", session)))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/projects/" + project + "/ingest-queue")
                .cookie(new jakarta.servlet.http.Cookie("mt_session", session)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(a.id.toString()))
        .andExpect(jsonPath("$[0].key_hash").doesNotExist())
        .andExpect(jsonPath("$[0].payload").doesNotExist());
    mvc.perform(
            delete(path)
                .cookie(new jakarta.servlet.http.Cookie("mt_session", session))
                .header("X-CSRF-Token", "test-csrf"))
        .andExpect(status().isOk());
    assertEquals(0, units());
    assertBudget();
    assertTrue(
        store.db.queryForObject(
            "SELECT payload IS NULL FROM ingest_accept_queue WHERE id=?", Boolean.class, a.id));
    mvc.perform(
            delete(path)
                .cookie(new jakarta.servlet.http.Cookie("mt_session", session))
                .header("X-CSRF-Token", "test-csrf"))
        .andExpect(status().isConflict());
  }
}
