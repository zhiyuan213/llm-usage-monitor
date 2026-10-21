package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "metertrail.secret=test-secret-at-least-32-characters-long",
      "metertrail.worker-enabled=false",
      "metertrail.secure-cookies=false",
      "metertrail.paddle-secret=local-webhook-test-secret",
      "metertrail.paddle-core-price=pri_core_test",
      "metertrail.paddle-pro-price=pri_pro_test"
    })
@AutoConfigureMockMvc
@Testcontainers
class IngestIntegrationTest {
  @Autowired PaymentsController payments;
  @Autowired PaymentReconciliation reconciliation;

  @Test
  void alertsNotifyEachRevisionOnceAndMergeNewEvidenceAcrossCooldown() {
    UUID rule = UUID.randomUUID();
    store.db.update(
        "INSERT INTO alert_rules(id,project_id,name,metric,threshold)"
            + " VALUES(?,?,'latency','latency_ms',1000)",
        rule,
        project);
    alertObject("first", 1850, "now()-interval '10 minutes'");
    Worker worker = new Worker(store, tx, mail, true, "test@test.local");
    worker.alerts();
    assertEquals(1, alertCount(rule));
    // Even after cooldown, the same abnormal object cannot trigger another notification.
    store.db.update(
        "UPDATE alert_rules SET last_triggered_at=now()-interval '2 hours' WHERE id=?", rule);
    worker.alerts();
    assertEquals(1, alertCount(rule));
    store.db.update("UPDATE alert_rules SET last_triggered_at=now() WHERE id=?", rule);
    alertObject("second", 2200, "now()-interval '59 minutes'");
    alertObject("third", 2100, "now()-interval '59 minutes'");
    worker.alerts();
    assertEquals(1, alertCount(rule));
    // Pending evidence survives its source event leaving the rolling window.
    store.db.update(
        "UPDATE objects SET started_at=now()-interval '2 hours' WHERE project_id=?", project);
    store.db.update(
        "UPDATE alert_rules SET last_triggered_at=now()-interval '2 hours' WHERE id=?", rule);
    worker.alerts();
    assertEquals(2, alertCount(rule));
    assertEquals(
        2L,
        store.db.queryForObject(
            "SELECT evidence_count FROM alert_events WHERE rule_id=? ORDER BY id DESC LIMIT 1",
            Long.class,
            rule));
    String body =
        store.secrets.open(
            store.db.queryForObject(
                "SELECT body FROM outbox WHERE recipient=? ORDER BY id DESC LIMIT 1",
                String.class,
                user + "@test.local"));
    assertTrue(body.contains("New matching objects / 新增匹配对象数: 2"));
    assertTrue(body.contains("2200"));
    assertTrue(body.contains("(UTC)"));
    store.db.update(
        "UPDATE alert_rules SET last_triggered_at=now()-interval '2 hours' WHERE id=?", rule);
    worker.alerts();
    assertEquals(2, alertCount(rule));
    store.db.update(
        "UPDATE objects SET revision=2,started_at=now() WHERE project_id=? AND object_id='first'",
        project);
    new Worker(store, tx, mail, true, "test@test.local").alerts();
    assertEquals(3, alertCount(rule));
  }

  @Test
  void alertMigrationPreservesPreviouslyNotifiedEvidence() {
    String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var flyway =
        org.flywaydb.core.Flyway.configure()
            .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .target("7")
            .load();
    try {
      flyway.migrate();
      var db =
          new org.springframework.jdbc.core.JdbcTemplate(
              new org.springframework.jdbc.datasource.DriverManagerDataSource(
                  pg.getJdbcUrl()
                      + (pg.getJdbcUrl().contains("?") ? "&" : "?")
                      + "currentSchema="
                      + schema,
                  pg.getUsername(),
                  pg.getPassword()));
      UUID rule = UUID.randomUUID();
      db.update("INSERT INTO users(id,email,verified) VALUES(?, 'upgrade@test.local',true)", user);
      db.update("INSERT INTO workspaces(id,owner_id,name) VALUES(?,?,'Upgrade')", workspace, user);
      db.update(
          "INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'Upgrade')", project, workspace);
      db.update(
          "INSERT INTO alert_rules(id,project_id,name,metric,threshold,last_triggered_at)"
              + " VALUES(?,?,'latency','latency_ms',1000,now()-interval '10 minutes')",
          rule,
          project);
      db.update(
          "INSERT INTO objects(project_id,type,object_id,revision,started_at,data)"
              + " VALUES(?,'trace','old',1,now()-interval '20 minutes','{\"durationMs\":1850}')",
          project);
      db.update(
          "INSERT INTO objects(project_id,type,object_id,revision,started_at,data)"
              + " VALUES(?,'trace','new',1,now(),'{\"durationMs\":1850}')",
          project);
      org.flywaydb.core.Flyway.configure()
          .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .load()
          .migrate();
      assertEquals(
          List.of("old"),
          db.queryForList(
              "SELECT object_id FROM alert_evidence WHERE notified_at IS NOT NULL", String.class));
    } finally {
      store.db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void alertsExcludeFutureAndNonmatchingObjectsAndAreSafeAcrossWorkers() throws Exception {
    UUID rule = UUID.randomUUID();
    store.db.update(
        "INSERT INTO alert_rules(id,project_id,name,metric,threshold)"
            + " VALUES(?,?,'latency','latency_ms',1000)",
        rule,
        project);
    alertObject("future", 1850, "now()+interval '2 hours'");
    alertObject("normal", 100, "now()");
    Worker worker = new Worker(store, tx, mail, true, "test@test.local");
    worker.alerts();
    assertEquals(0, alertCount(rule));
    alertObject("slow", 1850, "now()");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var futures =
          pool.invokeAll(
              List.of(Executors.callable(worker::alerts), Executors.callable(worker::alerts)));
      for (var future : futures) future.get();
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, alertCount(rule));
    assertEquals(
        1L,
        store.db.queryForObject(
            "SELECT evidence_count FROM alert_events WHERE rule_id=?", Long.class, rule));
  }

  @Test
  void ingestionRejectsFutureTimestampsButAllowsSmallClockSkew() {
    var future = event("trace", "future", 1);
    future.put("startedAt", Instant.now().plusSeconds(3600).toString());
    assertTrue(
        result(ingest.accept(project, batch(future)))
            .get("errors")
            .toString()
            .contains("INVALID_TIMESTAMP"));
    assertEquals(0, units());
    future.put("eventId", UUID.randomUUID().toString());
    future.put("startedAt", Instant.now().plusSeconds(120).toString());
    assertEquals(1, result(ingest.accept(project, batch(future))).get("accepted"));
  }

  @Test
  void errorAndCostRulesKeepWindowTriggerValueAndDoNotResendOldEvidence() {
    for (String metric : List.of("error_count", "cost_usd")) {
      UUID rule = UUID.randomUUID();
      store.db.update(
          "INSERT INTO alert_rules(id,project_id,name,metric,threshold) VALUES(?,?,?,?,2)",
          rule,
          project,
          metric,
          metric);
      String first = metric + "-first", second = metric + "-second", third = metric + "-third";
      alertObject(first, 100, "now()");
      alertObject(second, 100, "now()");
      store.db.update(
          "UPDATE objects SET cost=1,data=jsonb_set(data,'{status}','\"error\"') WHERE project_id=?"
              + " AND object_id IN (?,?)",
          project,
          first,
          second);
      Worker worker = new Worker(store, tx, mail, true, "test@test.local");
      worker.alerts();
      assertEquals(1, alertCount(rule));
      alertObject(third, 100, "now()");
      store.db.update(
          "UPDATE objects SET cost=1,data=jsonb_set(data,'{status}','\"error\"') WHERE project_id=?"
              + " AND object_id=?",
          project,
          third);
      worker.alerts();
      assertEquals(1, alertCount(rule));
      store.db.update(
          "UPDATE alert_rules SET last_triggered_at=now()-interval '2 hours' WHERE id=?", rule);
      worker.alerts();
      assertEquals(2, alertCount(rule));
      assertTrue(
          store
                  .db
                  .queryForObject(
                      "SELECT value FROM alert_events WHERE rule_id=? ORDER BY id DESC LIMIT 1",
                      java.math.BigDecimal.class,
                      rule)
                  .intValue()
              >= 3);
      assertEquals(
          1L,
          store.db.queryForObject(
              "SELECT evidence_count FROM alert_events WHERE rule_id=? ORDER BY id DESC LIMIT 1",
              Long.class,
              rule));
      store.db.update(
          "UPDATE alert_rules SET last_triggered_at=now()-interval '2 hours' WHERE id=?", rule);
      worker.alerts();
      assertEquals(2, alertCount(rule));
      store.db.update("UPDATE alert_rules SET enabled=false WHERE id=?", rule);
      // Keep fixtures for the next metric isolated.
      store.db.update("DELETE FROM objects WHERE project_id=?", project);
    }
  }

  void alertObject(String id, int duration, String timestamp) {
    store.db.update(
        "INSERT INTO objects(project_id,type,object_id,revision,started_at,data)"
            + " VALUES(?,'trace',?,1,"
            + timestamp
            + ",?::jsonb)",
        project,
        id,
        "{\"durationMs\":" + duration + ",\"status\":\"success\"}");
  }

  int alertCount(UUID rule) {
    return store.db.queryForObject(
        "SELECT count(*) FROM alert_events WHERE rule_id=?", Integer.class, rule);
  }

  @Test
  void reconciliationSkipsMissingCredentialsThroughTheRealTransactionProxy() {
    assertTrue(org.springframework.aop.support.AopUtils.isCglibProxy(payments));
    assertFalse(payments.reconciliationEnabled());
    assertDoesNotThrow(reconciliation::reconcile);
    var failure = assertThrows(ApiError.class, () -> payments.readSubscription("sub_synthetic"));
    assertEquals("PAYMENTS_NOT_CONFIGURED", failure.code);
  }

  @Test
  void permanentFailureCompensatesOnceAndCorrectedRevisionRereserves() throws Exception {
    var event = event("trace", "recoverable", 1);
    var batch = ingest.accept(project, batch(event));
    store.db.update(
        "UPDATE ingest_batches SET status='quarantined' WHERE id=?", batch.get("batchId"));
    UUID challenge =
        challenges.issue(user, "batch.cancel", project + ":" + batch.get("batchId"), null);
    String text =
        store.secrets.open(
            store.db.queryForObject(
                "SELECT body FROM outbox WHERE recipient=? ORDER BY id DESC LIMIT 1",
                String.class,
                user + "@test.local"));
    String code = text.substring(text.indexOf(": ") + 2, text.indexOf(": ") + 8);
    String token = Support.token();
    store.db.update(
        "INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1"
            + " hour')",
        Support.hash(token),
        user,
        "csrf-test");
    String path = "/api/projects/" + project + "/batches/" + batch.get("batchId") + "/cancel";
    String body =
        new ObjectMapper()
            .writeValueAsString(Map.of("challengeId", challenge, "code", code, "confirm", true));
    mvc.perform(
            post(path)
                .cookie(new jakarta.servlet.http.Cookie("mt_session", token))
                .header("X-CSRF-Token", "csrf-test")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.released").value(1));
    mvc.perform(
            post(path)
                .cookie(new jakarta.servlet.http.Cookie("mt_session", token))
                .header("X-CSRF-Token", "csrf-test")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.released").value(0));
    assertEquals(0, units());
    event.put("eventId", UUID.randomUUID().toString());
    event.put("revision", 2);
    ingest.accept(project, batch(event));
    ingest.accept(project, batch(event));
    assertEquals(1, units());
    assertEquals(
        1L,
        store.db.queryForObject(
            "SELECT sum(units) FROM usage_ledger WHERE workspace_id=?", Long.class, workspace));
  }

  @Test
  void paymentWebhookIsSignedIdempotentAndOrdered() throws Exception {
    UUID intent = UUID.randomUUID();
    store.db.update(
        "INSERT INTO checkout_intents(id,workspace_id,plan) VALUES(?,?,'Core')", intent, workspace);
    ObjectNode data = new ObjectMapper().createObjectNode();
    data.put("id", "sub_" + workspace).put("status", "active").put("customer_id", "ctm_test");
    data.putObject("custom_data").put("metertrail_intent", intent.toString());
    data.putArray("items").addObject().putObject("price").put("id", "pri_core_test");
    data.putObject("current_billing_period")
        .put("starts_at", Instant.now().minusSeconds(3600).toString())
        .put("ends_at", Instant.now().plusSeconds(86400).toString());
    ObjectNode event = new ObjectMapper().createObjectNode();
    event
        .put("event_id", "evt_" + UUID.randomUUID())
        .put("event_type", "subscription.activated")
        .put("occurred_at", Instant.now().toString())
        .set("data", data);
    String body = event.toString(), ts = String.valueOf(Instant.now().getEpochSecond());
    String signature =
        "ts=" + ts + ";h1=" + Support.hmac("local-webhook-test-secret", ts + ":" + body);
    mvc.perform(
            post("/webhooks/paddle")
                .header("Paddle-Signature", signature)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
    mvc.perform(
            post("/webhooks/paddle")
                .header("Paddle-Signature", signature)
                .contentType("application/json")
                .content(body))
        .andExpect(jsonPath("$.status").value("duplicate"));
    assertEquals("Core", store.workspace(user).get("plan"));
    event
        .put("event_id", "evt_" + UUID.randomUUID())
        .put("occurred_at", Instant.now().minusSeconds(3600).toString());
    ((ObjectNode) data.path("items").get(0).get("price")).put("id", "pri_pro_test");
    body = event.toString();
    signature = "ts=" + ts + ";h1=" + Support.hmac("local-webhook-test-secret", ts + ":" + body);
    mvc.perform(
            post("/webhooks/paddle")
                .header("Paddle-Signature", signature)
                .contentType("application/json")
                .content(body))
        .andExpect(jsonPath("$.status").value("stale"));
    assertEquals("Core", store.workspace(user).get("plan"));
    mvc.perform(
            post("/webhooks/paddle")
                .header("Paddle-Signature", "ts=" + ts + ";h1=invalid")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void gzipExpansionLimit() throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) {
      gzip.write("a".repeat(1048577).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    mvc.perform(
            post("/v1/events/batch")
                .header("Authorization", "Bearer " + key)
                .header("Content-Encoding", "gzip")
                .contentType("application/json")
                .content(bytes.toByteArray()))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void resetRevokesEverySession() throws Exception {
    String token = Support.token();
    store.db.update(
        "INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1"
            + " hour')",
        Support.hash(token),
        user,
        "csrf-test");
    UUID challenge = challenges.issue(user, "reset-password", user + "@test.local", null);
    String text =
        store.secrets.open(
            store.db.queryForObject(
                "SELECT body FROM outbox WHERE recipient=? ORDER BY id DESC LIMIT 1",
                String.class,
                user + "@test.local"));
    String code = text.substring(text.indexOf(": ") + 2, text.indexOf(": ") + 8);
    String body =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "email",
                    user + "@test.local",
                    "password",
                    "new-test-password-12345",
                    "challengeId",
                    challenge,
                    "code",
                    code));
    mvc.perform(post("/api/auth/reset").contentType("application/json").content(body))
        .andExpect(status().isOk());
    mvc.perform(get("/api/me").cookie(new jakarta.servlet.http.Cookie("mt_session", token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void poisonBatchIsQuarantinedWithoutBlockingFollowingBatch() {
    var bad = ingest.accept(project, batch(event("trace", "poison", 1)));
    store.db.update(
        "UPDATE ingest_events SET payload=jsonb_set(payload,'{startedAt}','\"invalid\"') WHERE"
            + " batch_id=?",
        bad.get("batchId"));
    store.db.update("UPDATE ingest_batches SET attempts=9 WHERE id=?", bad.get("batchId"));
    var good = ingest.accept(project, batch(event("trace", "healthy", 1)));
    new Worker(store, tx, mail, true, "test@test.local").process();
    assertEquals("quarantined", result(bad).get("status"));
    assertEquals("completed", result(good).get("status"));
    assertEquals(2, units());
  }

  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", pg::getJdbcUrl);
    r.add("spring.datasource.username", pg::getUsername);
    r.add("spring.datasource.password", pg::getPassword);
  }

  @Autowired Store store;
  @Autowired IngestService ingest;
  @Autowired MockMvc mvc;
  @Autowired Challenges challenges;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;
  @Autowired MailDelivery mail;
  @MockBean RateLimits rates;
  UUID user, workspace, project;
  String key;

  @BeforeEach
  void setup() {
    user = UUID.randomUUID();
    workspace = UUID.randomUUID();
    project = UUID.randomUUID();
    key = "lm_live_" + Support.token();
    store.db.update(
        "INSERT INTO users(id,email,verified) VALUES(?,?,true)", user, user + "@test.local");
    store.db.update("INSERT INTO workspaces(id,owner_id,name) VALUES(?,?,'Test')", workspace, user);
    store.db.update(
        "INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'Test')", project, workspace);
    store.db.update(
        "INSERT INTO api_keys(id,project_id,name,prefix,key_hash)"
            + " VALUES(?,?,'Test','lm_live_test',?)",
        UUID.randomUUID(),
        project,
        Support.hash(key));
  }

  ObjectNode event(String type, String object, int revision) {
    ObjectNode e = new ObjectMapper().createObjectNode();
    e.put("eventId", UUID.randomUUID().toString());
    e.put("type", type);
    e.put("objectId", object);
    e.put("revision", revision);
    e.put("startedAt", Instant.now().toString());
    e.put("traceId", "0123456789abcdef0123456789abcdef");
    ObjectNode d = e.putObject("data");
    d.put("name", "request");
    d.put("status", "success");
    d.put("durationMs", 150);
    if (type.equals("observation")) {
      e.put("spanId", "0123456789abcdef");
      d.put("observationType", "generation");
      d.put("provider", "example");
      d.put("model", "test-model");
      d.putObject("usage")
          .put("inputTokens", 1000)
          .put("outputTokens", 200)
          .put("cachedInputTokens", 300);
    }
    if (type.equals("score")) {
      d.removeAll();
      d.put("name", "quality");
      d.put("targetType", "trace");
      d.put("targetId", "trace-1");
      d.put("valueType", "numeric");
      d.put("value", 0.9);
    }
    return e;
  }

  ObjectNode batch(JsonNode... events) {
    ObjectNode b = new ObjectMapper().createObjectNode();
    b.put("schemaVersion", "1.0");
    b.putObject("agent")
        .put("serviceName", "test")
        .put("environment", "test")
        .put("instanceId", "test");
    ArrayNode a = b.putArray("events");
    for (JsonNode e : events) a.add(e);
    return b;
  }

  long units() {
    return store.db.queryForObject(
        "SELECT used_units FROM workspaces WHERE id=?", Long.class, workspace);
  }

  Map<String, Object> result(Map<String, Object> r) {
    return store.db.queryForMap("SELECT * FROM ingest_batches WHERE id=?", r.get("batchId"));
  }

  @Test
  void threeObjectsDuplicatesUpdatesAndWorkerRecovery() {
    var t = event("trace", "trace-1", 1);
    var o = event("observation", "obs-1", 1);
    var s = event("score", "score-1", 1);
    var b = batch(t, o, s);
    assertEquals(3, result(ingest.accept(project, b)).get("accepted"));
    assertEquals(3, units());
    assertEquals(3, result(ingest.accept(project, b)).get("duplicates"));
    o.put("eventId", UUID.randomUUID().toString());
    assertEquals(1, result(ingest.accept(project, batch(o))).get("duplicates"));
    o.put("eventId", UUID.randomUUID().toString());
    o.put("revision", 2);
    ((ObjectNode) o.get("data")).put("durationMs", 200);
    ingest.accept(project, batch(o));
    assertEquals(3, units());
    Worker worker = new Worker(store, tx, mail, true, "test@test.local");
    worker.process();
    worker.process();
    assertEquals(
        3,
        store.db.queryForObject(
            "SELECT count(*) FROM objects WHERE project_id=?", Integer.class, project));
    assertEquals(
        2L,
        store.db.queryForObject(
            "SELECT revision FROM objects WHERE project_id=? AND object_id='obs-1'",
            Long.class,
            project));
    assertNull(
        store.db.queryForObject(
            "SELECT cost FROM objects WHERE project_id=? AND object_id='obs-1'",
            java.math.BigDecimal.class,
            project));
  }

  @Test
  void eventAndRevisionConflicts() {
    var e = event("trace", "trace-1", 1);
    ingest.accept(project, batch(e));
    ((ObjectNode) e.get("data")).put("name", "changed");
    var conflict = result(ingest.accept(project, batch(e)));
    assertTrue(conflict.get("errors").toString().contains("EVENT_ID_CONFLICT"));
    e.put("eventId", UUID.randomUUID().toString());
    assertTrue(
        result(ingest.accept(project, batch(e)))
            .get("errors")
            .toString()
            .contains("REVISION_CONFLICT"));
    assertEquals(1, units());
  }

  @Test
  void concurrencyCannotOversell() throws Exception {
    store.db.update("UPDATE workspaces SET used_units=49999 WHERE id=?", workspace);
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Callable<Boolean>> jobs = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        int j = i;
        jobs.add(
            () -> {
              try {
                ingest.accept(project, batch(event("trace", "t" + j, 1)));
                return true;
              } catch (ApiError e) {
                assertEquals("QUOTA_EXCEEDED", e.code);
                return false;
              }
            });
      }
      long successes = 0;
      for (var f : pool.invokeAll(jobs)) if (f.get()) successes++;
      assertEquals(1, successes);
      assertEquals(50000, units());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void capIsAtomicAndUpdatesSurviveExhaustion() {
    store.db.update(
        "UPDATE workspaces SET plan='Core',used_units=100000,auto_overage=true,overage_cap=0.00008"
            + " WHERE id=?",
        workspace);
    var e = event("trace", "t", 1);
    ingest.accept(project, batch(e));
    assertThrows(ApiError.class, () -> ingest.accept(project, batch(event("trace", "x", 1))));
    e.put("revision", 2);
    e.put("eventId", UUID.randomUUID().toString());
    ingest.accept(project, batch(e));
    assertEquals(100001, units());
  }

  @Test
  void partialFailureAndUnknownUsage() {
    var bad = event("observation", "bad", 1);
    ((ObjectNode) bad.path("data").path("usage")).put("cachedInputTokens", 2000);
    var r = result(ingest.accept(project, batch(event("trace", "ok", 1), bad)));
    assertEquals(1, r.get("accepted"));
    assertEquals(1, r.get("rejected"));
    assertEquals(1, units());
  }

  @Test
  void apiKeyCannotReadOtherProjectBatch() throws Exception {
    var r = ingest.accept(project, batch(event("trace", "t", 1)));
    mvc.perform(get("/v1/batches/" + r.get("batchId")).header("Authorization", "Bearer " + key))
        .andExpect(status().isOk());
    UUID other = UUID.randomUUID();
    store.db.update(
        "INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'Other')", other, workspace);
    String otherKey = "lm_live_" + Support.token();
    store.db.update(
        "INSERT INTO api_keys(id,project_id,name,prefix,key_hash) VALUES(?,?,'other','lm_live_',?)",
        UUID.randomUUID(),
        other,
        Support.hash(otherKey));
    mvc.perform(
            get("/v1/batches/" + r.get("batchId")).header("Authorization", "Bearer " + otherKey))
        .andExpect(status().isNotFound());
    store.db.update("UPDATE api_keys SET revoked_at=now() WHERE project_id=?", project);
    mvc.perform(get("/v1/batches/" + r.get("batchId")).header("Authorization", "Bearer " + key))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void verificationIsBoundedSingleUseAndTargetBound() {
    UUID c = challenges.issue(user, "key.create", "project:key", null);
    String body =
        store.secrets.open(
            store.db.queryForObject(
                "SELECT body FROM outbox WHERE recipient=? ORDER BY id DESC LIMIT 1",
                String.class,
                user + "@test.local"));
    String code = body.substring(body.indexOf(": ") + 2, body.indexOf(": ") + 8);
    assertThrows(
        ApiError.class,
        () -> challenges.consume(user, "key.create", "changed", c.toString(), code, null));
    challenges.consume(user, "key.create", "project:key", c.toString(), code, null);
    assertThrows(
        ApiError.class,
        () -> challenges.consume(user, "key.create", "project:key", c.toString(), code, null));
  }

  @Test
  void fiveWrongCodesLockChallenge() {
    UUID c = challenges.issue(user, "key.create", "target", null);
    for (int i = 0; i < 6; i++)
      assertThrows(
          ApiError.class,
          () -> challenges.consume(user, "key.create", "target", c.toString(), "not-a-code", null));
    assertEquals(
        6,
        store.db.queryForObject(
            "SELECT attempts FROM verification_challenges WHERE id=?", Integer.class, c));
  }

  @Test
  void requestSizesAndCsrf() throws Exception {
    mvc.perform(
            post("/v1/events/batch")
                .header("Authorization", "Bearer " + key)
                .contentType("application/json")
                .content("x".repeat(1048577)))
        .andExpect(status().isPayloadTooLarge());
    String token = Support.token();
    store.db.update(
        "INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1"
            + " hour')",
        Support.hash(token),
        user,
        "csrf-test");
    mvc.perform(
            post("/api/projects")
                .cookie(new jakarta.servlet.http.Cookie("mt_session", token))
                .contentType("application/json")
                .content("{\"name\":\"x\",\"environment\":\"test\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void cachedTokensAreNotDoubleCounted() {
    store.db.update(
        "INSERT INTO"
            + " price_versions(id,project_id,provider,model,effective_at,input_rate,output_rate,cached_rate)"
            + " VALUES(?,?,'example','test-model',now()-interval '1 day',2,4,0.5)",
        UUID.randomUUID(),
        project);
    ingest.accept(project, batch(event("observation", "obs", 1)));
    new Worker(store, tx, mail, true, "test@test.local").process();
    var cost =
        store.db.queryForObject(
            "SELECT cost FROM objects WHERE project_id=?", java.math.BigDecimal.class, project);
    assertEquals(0, cost.compareTo(new java.math.BigDecimal("0.00235")));
  }
}
