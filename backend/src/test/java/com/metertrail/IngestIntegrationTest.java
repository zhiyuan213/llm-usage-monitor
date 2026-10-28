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
      "metertrail.ingest.pipeline.enabled=false",
      "metertrail.usage-collection-enabled=true",
      "metertrail.secure-cookies=false",
      "metertrail.paddle-secret=local-webhook-test-secret",
      "metertrail.paddle-core-price=pri_core_test",
      "metertrail.paddle-pro-price=pri_pro_test"
    })
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class IngestIntegrationTest {
  // Stop scheduled tasks before Testcontainers stops PostgreSQL; Spring closes the context afterward.
  @AfterAll static void closeTestContext(@Autowired org.springframework.context.ConfigurableApplicationContext context) {
    context.getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
        .values().forEach(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler::destroy);
  }

  UUID pack() {
    store.db.update("UPDATE workspaces SET plan='Core',subscription_status='active',subscription_id=?,period_end=now()+interval '1 month' WHERE id=?","sub_"+workspace,workspace);
    UUID id=UUID.randomUUID();
    store.db.update("INSERT INTO usage_charges(id,workspace_id,subscription_id,period_start,period_end,kind,units,amount,status) SELECT ?,id,subscription_id,period_start,period_end,'addon',100000,8,'submitted' FROM workspaces WHERE id=?",id,workspace);
    return id;
  }
  JsonNode packTransaction(UUID id,String status,String cents) {
    var price=new HashMap<String,Object>();price.put("description",ChargeLedger.marker(id));price.put("billing_cycle",null);price.put("unit_price",Map.of("amount",cents,"currency_code","USD"));
    return new ObjectMapper().valueToTree(Map.of("id","txn_"+id,"status",status,"currency_code","USD","custom_data",Map.of("metertrail_charge",id),"items",List.of(Map.of("quantity",1,"price",price)),"details",Map.of("totals",Map.of("grand_total","800"))));
  }
  void packEvent(UUID id,String status,String cents) {
    JsonNode data=packTransaction(id,status,cents);
    JsonNode event=new ObjectMapper().valueToTree(Map.of("event_id",UUID.randomUUID(),"event_type","transaction.updated","occurred_at",Instant.now().toString(),"data",data));
    tx.executeWithoutResult(x->payments.applyEvent(event));
  }
  @Test void purchasedPackIsVerifiedOnceAndRefundCannotRegrantIt() {
    UUID id=pack();
    packEvent(id,"ready","800");assertEquals(0L,store.db.queryForObject("SELECT addon_units FROM workspaces WHERE id=?",Long.class,workspace));
    packEvent(id,"completed","800");packEvent(id,"completed","800");
    assertEquals(100000L,store.db.queryForObject("SELECT addon_units FROM workspaces WHERE id=?",Long.class,workspace));
    JsonNode refund=Support.parse("{\"action\":\"refund\",\"status\":\"approved\",\"transaction_id\":\"txn_"+id+"\"}");
    tx.executeWithoutResult(x->{var w=store.db.queryForMap("SELECT * FROM workspaces WHERE id=? FOR UPDATE",workspace);ChargeLedger.refund(store,w,refund);ChargeLedger.refund(store,w,refund);});
    packEvent(id,"completed","800");
    assertEquals(0L,store.db.queryForObject("SELECT addon_units FROM workspaces WHERE id=?",Long.class,workspace));
  }
  @Test void wrongPriceDeclineAndLatePaymentNeverGrantPack() {
    UUID id=pack();
    assertThrows(ApiError.class,()->packEvent(id,"completed","1"));
    packEvent(id,"canceled","800");assertEquals(0L,store.db.queryForObject("SELECT addon_units FROM workspaces WHERE id=?",Long.class,workspace));
    store.db.update("UPDATE workspaces SET period_start=period_start+interval '1 month',period_end=period_end+interval '1 month' WHERE id=?",workspace);
    packEvent(id,"completed","800");
    assertEquals("refund_required",store.db.queryForObject("SELECT status FROM usage_charges WHERE id=?",String.class,id));
    assertEquals(0L,store.db.queryForObject("SELECT addon_units FROM workspaces WHERE id=?",Long.class,workspace));
  }
  @Test void packRefundDoesNotInventUnconsentedOverageAndNewUsageCountsSeparately() {
    UUID id=pack();packEvent(id,"completed","800");
    store.db.update("UPDATE workspaces SET used_units=199999,addon_consumed=99999 WHERE id=?",workspace);
    store.db.update("UPDATE usage_charges SET consumed_units=99999 WHERE id=?",id);
    ingest.accept(project,batch(event("trace","pre-refund",1)));
    assertEquals(0,ChargeLedger.accrued(store.workspace(user)).signum());
    tx.executeWithoutResult(x->ChargeLedger.refund(store,store.workspace(user),Support.parse("{\"action\":\"refund\",\"status\":\"approved\",\"transaction_id\":\"txn_"+id+"\"}")));
    assertEquals(0,ChargeLedger.accrued(store.workspace(user)).signum());
    assertThrows(ApiError.class,()->ingest.accept(project,batch(event("trace","unconsented",1))));
    store.db.update("UPDATE workspaces SET auto_overage=true,overage_cap=1 WHERE id=?",workspace);
    ingest.accept(project,batch(event("trace","authorized",1)));
    assertEquals(0,new java.math.BigDecimal("0.00008").compareTo(ChargeLedger.accrued(store.workspace(user))));
  }

  @Test void buyingPackAfterOverageCoversFutureUsageWithoutRepricingPastCharges() {
    UUID id=pack();
    store.db.update("UPDATE workspaces SET used_units=150000,overage_units=50000 WHERE id=?",workspace);
    packEvent(id,"completed","800");
    ingest.accept(project,batch(event("trace","post-overage-pack",1)));
    var w=store.workspace(user);
    assertEquals(50000L,((Number)w.get("overage_units")).longValue());
    assertEquals(1L,((Number)w.get("addon_consumed")).longValue());
    assertEquals(0,new java.math.BigDecimal("4").compareTo(ChargeLedger.accrued(w)));
  }

  @Test void publicQuotaReportsRealAvailablePacksAndBlocksInactiveSubscriptions() throws Exception {
    UUID id=pack();packEvent(id,"completed","800");
    mvc.perform(get("/v1/quota").header("Authorization","Bearer "+key))
        .andExpect(status().isOk()).andExpect(jsonPath("$.addon_units").value(100000)).andExpect(jsonPath("$.available_units").value(200000));
    ingest.accept(project,batch(event("trace","quota-pack",1)));
    mvc.perform(get("/v1/quota").header("Authorization","Bearer "+key)).andExpect(jsonPath("$.available_units").value(199999));
    store.db.update("UPDATE workspaces SET subscription_status='canceled' WHERE id=?",workspace);
    mvc.perform(get("/v1/quota").header("Authorization","Bearer "+key)).andExpect(jsonPath("$.available_units").value(0));
  }

  @Test void addonQuotaAndImmutablePeriodSettlementShareTheWorkspaceLock() {
    UUID id=pack();packEvent(id,"completed","800");
    store.db.update("UPDATE usage_charges SET consumed_units=100000 WHERE id=?",id);
    store.db.update("UPDATE workspaces SET addon_consumed=100000,used_units=200000 WHERE id=?",workspace);
    assertThrows(ApiError.class,()->ingest.accept(project,batch(event("trace","after-pack",1))));
    store.db.update("UPDATE workspaces SET used_units=199999,addon_consumed=99999 WHERE id=?",workspace);
    store.db.update("UPDATE usage_charges SET consumed_units=99999 WHERE id=?",id);
    ingest.accept(project,batch(event("trace","last-pack",1)));assertEquals(200000,units());
    store.db.update("UPDATE workspaces SET used_units=300000,overage_units=100000 WHERE id=?",workspace);
    tx.executeWithoutResult(x->{var w=store.db.queryForMap("SELECT * FROM workspaces WHERE id=? FOR UPDATE",workspace);ChargeLedger.close(store,w);ChargeLedger.close(store,w);});
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM usage_charges WHERE workspace_id=? AND kind='overage'",Integer.class,workspace));
    assertEquals(0,new java.math.BigDecimal("8.00").compareTo(store.db.queryForObject("SELECT amount FROM usage_charges WHERE workspace_id=? AND kind='overage'",java.math.BigDecimal.class,workspace)));
  }

  @Test
  void checkoutConfigIsPublicAndDoesNotExposeServerCredentials() throws Exception {
    mvc.perform(get("/v1/checkout/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.token").value(""))
        .andExpect(jsonPath("$.apiKey").doesNotExist())
        .andExpect(jsonPath("$.secret").doesNotExist());
  }

  @Autowired PaymentsController payments;
  @Autowired PaymentReconciliation reconciliation;

  @Test
  void rememberedLoginHasMatchingCookieAndDatabaseExpiryAndLogoutRevokesIt() throws Exception {
    String password = "synthetic-password-12345";
    store.db.update(
        "UPDATE users SET password_hash=? WHERE id=?",
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(password),
        user);
    for (boolean remember : List.of(false, true)) {
      var response =
          mvc.perform(
                  post("/api/auth/login")
                      .contentType("application/json")
                      .content(
                          new ObjectMapper()
                              .writeValueAsString(
                                  Map.of(
                                      "email",
                                      user + "@test.local",
                                      "password",
                                      password,
                                      "rememberMe",
                                      remember))))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse();
      String cookie = response.getHeader("Set-Cookie");
      long seconds = remember ? 2592000 : 43200;
      assertTrue(cookie.contains("Max-Age=" + seconds));
      assertTrue(cookie.contains("HttpOnly"));
      assertTrue(cookie.contains("SameSite=Lax"));
      String token = cookie.substring("mt_session=".length(), cookie.indexOf(';'));
      var expiry =
          store
              .db
              .queryForObject(
                  "SELECT expires_at FROM sessions WHERE token_hash=?",
                  java.sql.Timestamp.class,
                  Support.hash(token))
              .toInstant();
      assertTrue(
          Math.abs(java.time.Duration.between(Instant.now(), expiry).getSeconds() - seconds) < 10);
      var browserCookie = new jakarta.servlet.http.Cookie("mt_session", token);
      // A new HTTP request needs only the saved cookie, not the password or in-memory frontend
      // state.
      mvc.perform(get("/api/me").cookie(browserCookie))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.expiresAt").exists());
      String csrf = Support.parse(response.getContentAsString()).path("csrf").asText();
      mvc.perform(
              post("/api/logout")
                  .cookie(browserCookie)
                  .header("X-CSRF-Token", csrf)
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isOk())
          .andExpect(
              header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
      mvc.perform(get("/api/me").cookie(browserCookie)).andExpect(status().isUnauthorized());
    }
  }

  @Test
  void rememberedSessionExpiresServerSideAndProductionCookieIsSecure() throws Exception {
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    new AuthController(
            store,
            challenges,
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4),
            rates,
            true)
        .session(user, response, true);
    String cookie = response.getHeader("Set-Cookie");
    assertTrue(cookie.contains("Secure"));
    assertTrue(cookie.contains("HttpOnly"));
    String token = cookie.substring("mt_session=".length(), cookie.indexOf(';'));
    store.db.update(
        "UPDATE sessions SET expires_at=now()-interval '1 second' WHERE token_hash=?",
        Support.hash(token));
    mvc.perform(get("/api/me").cookie(new jakarta.servlet.http.Cookie("mt_session", token)))
        .andExpect(status().isUnauthorized());
  }

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
  void billingShowsOwnRefundAdjustmentsAndScheduledCancellationOnly() throws Exception {
    UUID otherUser=UUID.randomUUID(), otherWorkspace=UUID.randomUUID();
    store.db.update("INSERT INTO users(id,email,verified) VALUES(?,?,true)",otherUser,otherUser+"@test.local");
    store.db.update("INSERT INTO workspaces(id,owner_id,name) VALUES(?,?,'Other')",otherWorkspace,otherUser);
    for(UUID id:List.of(workspace,otherWorkspace))
      store.db.update("INSERT INTO payment_adjustments(id,workspace_id,transaction_id,action,status,amount,currency,updated_at) VALUES(?,?,?,'refund','approved',1,'USD',now())","adj_"+id,id,"txn_"+id);
    store.db.update("UPDATE workspaces SET scheduled_change=?::jsonb WHERE id=?","{\"action\":\"cancel\",\"effective_at\":\"2026-11-23T00:00:00Z\"}",workspace);
    String token=Support.token();
    store.db.update("INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1 hour')",Support.hash(token),user,"csrf-test");
    mvc.perform(get("/api/billing").cookie(new jakarta.servlet.http.Cookie("mt_session",token)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.adjustments.length()").value(1))
        .andExpect(jsonPath("$.adjustments[0].transaction_id").value("txn_"+workspace))
        .andExpect(jsonPath("$.workspace.scheduled_change.action").value("cancel"));
  }

  @Test
  void renewalResetsOnceAndSamePeriodPlanChangePreservesUsage() throws Exception {
    Instant start=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).minusSeconds(86400), end=start.plusSeconds(2592000);
    store.db.update("UPDATE workspaces SET plan='Core',subscription_id=?,subscription_status='active',period_start=?,period_end=?,used_units=37 WHERE id=?",
        "sub_"+workspace, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end), workspace);
    var data=new ObjectMapper().createObjectNode();
    data.put("id","sub_"+workspace).put("status","active");
    data.putArray("items").addObject().putObject("price").put("id","pri_pro_test");
    data.putObject("current_billing_period").put("starts_at",start.toString()).put("ends_at",end.toString());
    var event=new ObjectMapper().createObjectNode();
    event.put("event_type","subscription.updated").set("data",data);
    Instant occurred=Instant.now();
    for(int step=0;step<5;step++) {
      if(step==1) {
        ((ObjectNode)data.path("current_billing_period")).put("starts_at",end.toString()).put("ends_at",end.plusSeconds(2592000).toString());
      }
      if(step==2) store.db.update("UPDATE workspaces SET used_units=9 WHERE id=?",workspace);
      if(step==3) { // A newly delivered old period must not roll billing back, even with a newer event timestamp.
        ((ObjectNode)data.path("current_billing_period")).put("starts_at",start.toString()).put("ends_at",end.toString());
      }
      if(step==4) data.put("status","past_due");
      event.put("event_id","evt_"+UUID.randomUUID()).put("occurred_at",occurred.plusSeconds(step).toString());
      String body=event.toString(), ts=String.valueOf(Instant.now().getEpochSecond());
      mvc.perform(post("/webhooks/paddle").header("Paddle-Signature","ts="+ts+";h1="+Support.hmac("local-webhook-test-secret",ts+":"+body)).contentType("application/json").content(body)).andExpect(status().isOk());
      assertEquals(step==0 ? 37 : step==1 ? 0 : 9,((Number)store.workspace(user).get("used_units")).intValue());
      assertEquals("Pro",store.workspace(user).get("plan"));
    }
    assertEquals("past_due",store.workspace(user).get("subscription_status"));
    assertEquals(end,((java.sql.Timestamp)store.workspace(user).get("period_start")).toInstant());
  }

  ObjectNode renewalEvent(Instant start, Instant end) {
    var event=new ObjectMapper().createObjectNode();
    event.put("event_id","evt_"+UUID.randomUUID()).put("event_type","subscription.updated").put("occurred_at",Instant.now().toString());
    var data=event.putObject("data");data.put("id","sub_"+workspace).put("status","active");
    data.putArray("items").addObject().putObject("price").put("id","pri_core_test");
    data.putObject("current_billing_period").put("starts_at",start.toString()).put("ends_at",end.toString());
    return event;
  }

  @Test void renewalExpiresPacksPreservesHistoryAndLateRefundCannotRemoveNewPack() {
    UUID oldPack=pack();packEvent(oldPack,"completed","800");
    var old=store.workspace(user);
    Instant next=((java.sql.Timestamp)old.get("period_end")).toInstant();
    store.db.update("UPDATE workspaces SET used_units=100007,addon_consumed=7 WHERE id=?",workspace);
    store.db.update("UPDATE usage_charges SET consumed_units=7 WHERE id=?",oldPack);
    var renewal=renewalEvent(next,next.plusSeconds(2592000));
    tx.executeWithoutResult(x->payments.applyEvent(renewal));
    assertEquals(0,units());
    assertEquals(0L,((Number)store.workspace(user).get("addon_units")).longValue());
    assertEquals(7L,store.db.queryForObject("SELECT consumed_units FROM usage_charges WHERE id=?",Long.class,oldPack));
    assertEquals(100007L,store.db.queryForObject("SELECT units FROM usage_charges WHERE workspace_id=? AND kind='overage'",Long.class,workspace));
    UUID newPack=pack();packEvent(newPack,"completed","800");
    tx.executeWithoutResult(x->ChargeLedger.refund(store,store.workspace(user),Support.parse("{\"action\":\"refund\",\"status\":\"approved\",\"transaction_id\":\"txn_"+oldPack+"\"}")));
    assertEquals(100000L,((Number)store.workspace(user).get("addon_units")).longValue());
    assertEquals("duplicate",tx.execute(x->payments.applyEvent(renewal)).get("status"));
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM usage_charges WHERE workspace_id=? AND kind='overage'",Integer.class,workspace));
  }

  @RepeatedTest(3) void concurrentRenewalsAndIngestConserveUsageAndFreezeOneOldInvoice() throws Exception {
    pack();
    store.db.update("UPDATE workspaces SET used_units=112500,overage_units=12500,auto_overage=true,overage_cap=10 WHERE id=?",workspace);
    Instant next=((java.sql.Timestamp)store.workspace(user).get("period_end")).toInstant();
    var renewal=renewalEvent(next,next.plusSeconds(2592000));
    var pool=Executors.newFixedThreadPool(28);var start=new CountDownLatch(1);
    var futures=new ArrayList<Future<?>>();
    try {
      for(int i=0;i<24;i++) { final int n=i; futures.add(pool.submit(()->{start.await();ingest.accept(project,batch(event("trace","boundary-"+n,1)));return null;})); }
      for(int i=0;i<4;i++) futures.add(pool.submit(()->{start.await();tx.executeWithoutResult(x->payments.applyEvent(renewal));return null;}));
      start.countDown();for(var future:futures)future.get(30,TimeUnit.SECONDS);
    } finally {pool.shutdownNow();}
    var closed=store.db.queryForMap("SELECT * FROM usage_charges WHERE workspace_id=? AND kind='overage'",workspace);
    long oldUsage=((Number)closed.get("units")).longValue();
    assertEquals(112524L,oldUsage+units());
    assertEquals(oldUsage-112500,store.db.queryForObject("SELECT count(*) FROM usage_ledger WHERE workspace_id=? AND period_start=?",Long.class,workspace,closed.get("period_start")));
    assertEquals((long)units(),store.db.queryForObject("SELECT count(*) FROM usage_ledger WHERE workspace_id=? AND period_start=?",Long.class,workspace,store.workspace(user).get("period_start")));
    assertEquals(0L,((Number)store.workspace(user).get("overage_units")).longValue());
    assertEquals(0,new java.math.BigDecimal("1.00").compareTo((java.math.BigDecimal)closed.get("amount")));
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM usage_charges WHERE workspace_id=? AND kind='overage'",Integer.class,workspace));
    assertEquals(24,store.db.queryForObject("SELECT count(*) FROM usage_ledger WHERE workspace_id=?",Integer.class,workspace));
  }

  @Test void completedPackIsMonotonicAgainstOlderOrLaterFailureAndConflictingTransaction() {
    UUID id=pack();packEvent(id,"completed","800");
    packEvent(id,"canceled","800");
    assertEquals("paid",store.db.queryForObject("SELECT status FROM usage_charges WHERE id=?",String.class,id));
    var conflicting=(ObjectNode)packTransaction(id,"completed","800");conflicting.put("id","txn_conflict");
    assertThrows(ApiError.class,()->tx.executeWithoutResult(x->ChargeLedger.transaction(store,store.workspace(user),conflicting,Instant.now())));
    assertEquals(100000L,((Number)store.workspace(user).get("addon_units")).longValue());
    assertEquals("txn_"+id,store.db.queryForObject("SELECT transaction_id FROM usage_charges WHERE id=?",String.class,id));
  }

  @Test void unknownPaymentReconcilesWithoutAnotherPostAndGrantsOnlyOnce() {
    UUID id=pack();
    store.db.update("UPDATE usage_charges SET status='unknown',transaction_id=? WHERE id=?","txn_"+id,id);
    var provider=org.mockito.Mockito.mock(PaymentsController.class);
    var data=(ObjectNode)packTransaction(id,"completed","800");data.put("updated_at",Instant.now().toString());
    org.mockito.Mockito.when(provider.readUsageTransaction("txn_"+id)).thenReturn(new ObjectMapper().createObjectNode().set("data",data));
    org.mockito.Mockito.when(provider.applyEvent(org.mockito.ArgumentMatchers.any())).thenAnswer(call->payments.applyEvent(call.getArgument(0)));
    var service=new UsageCharges(store,provider,tx,true,false,"https://example.test");
    var charge=store.db.queryForMap("SELECT * FROM usage_charges WHERE id=?",id);
    service.reconcile(charge);service.reconcile(charge);
    assertEquals("paid",store.db.queryForObject("SELECT status FROM usage_charges WHERE id=?",String.class,id));
    assertEquals(100000L,((Number)store.workspace(user).get("addon_units")).longValue());
    org.mockito.Mockito.verify(provider,org.mockito.Mockito.never()).postUsage(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap());
  }

  @Test void unavailableReconciliationEvidenceKeepsUnknownAndNoCredit() {
    UUID id=pack();
    store.db.update("UPDATE usage_charges SET status='unknown',transaction_id=? WHERE id=?","txn_"+id,id);
    var provider=org.mockito.Mockito.mock(PaymentsController.class);
    org.mockito.Mockito.when(provider.readUsageTransaction("txn_"+id)).thenThrow(new RuntimeException("simulated provider outage"));
    var service=new UsageCharges(store,provider,tx,true,false,"https://example.test");
    assertThrows(RuntimeException.class,()->service.reconcile(store.db.queryForMap("SELECT * FROM usage_charges WHERE id=?",id)));
    assertEquals("unknown",store.db.queryForObject("SELECT status FROM usage_charges WHERE id=?",String.class,id));
    assertEquals(0L,((Number)store.workspace(user).get("addon_units")).longValue());
    org.mockito.Mockito.verify(provider,org.mockito.Mockito.never()).postUsage(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap());
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
        "INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '30"
            + " days')",
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

  @Test
  void lostWorkerDatabaseConnectionRollsBackPartialBatchAndRestartRecoversOnce() throws Exception {
    var receipt=ingest.accept(project,batch(event("observation","disconnect-first",1),event("trace","disconnect-second",1)));
    Object batchId=receipt.get("batchId");
    store.db.update("UPDATE ingest_batches SET created_at=now()-interval '1 year' WHERE id=?",batchId);
    String function="worker_fault_"+UUID.randomUUID().toString().replace("-","");
    store.db.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+project+"'::uuid AND NEW.object_id='disconnect-second' THEN PERFORM pg_sleep(30); END IF; RETURN NEW; END $$");
    store.db.execute("CREATE TRIGGER "+function+" BEFORE INSERT ON objects FOR EACH ROW EXECUTE FUNCTION "+function+"()");
    var pid=new java.util.concurrent.atomic.AtomicInteger();var connected=new CountDownLatch(1);
    var faultTx=new org.springframework.transaction.support.TransactionTemplate(tx.getTransactionManager()) {
      @Override public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
        return super.execute(status->{pid.set(store.db.queryForObject("SELECT pg_backend_pid()",Integer.class));connected.countDown();return action.doInTransaction(status);});
      }
    };
    var worker=CompletableFuture.runAsync(()->new Worker(store,faultTx,mail,true,"test@test.local").process());
    try {
      assertTrue(connected.await(10,TimeUnit.SECONDS));int workerPid=pid.get();boolean sleeping=false;
      for(int attempt=0;attempt<100;attempt++) {
        sleeping=store.db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event='PgSleep'",Integer.class,workerPid)==1;
        if(sleeping)break;Thread.sleep(100);
      }
      assertTrue(sleeping,"Worker must reach second-object fault after first-object writes");
      assertTrue(store.db.queryForObject("SELECT pg_terminate_backend(?)",Boolean.class,workerPid));
      worker.get(20,TimeUnit.SECONDS);
    } finally {
      store.db.execute("DROP TRIGGER IF EXISTS "+function+" ON objects");
      store.db.execute("DROP FUNCTION IF EXISTS "+function+"()");
    }
    assertEquals("pending",result(receipt).get("status"));
    assertEquals(0,store.db.queryForObject("SELECT count(*) FROM objects WHERE project_id=?",Integer.class,project));
    assertEquals(0,store.db.queryForObject("SELECT count(*) FROM cost_entries WHERE project_id=?",Integer.class,project));
    assertEquals(0,store.db.queryForObject("SELECT count(*) FROM ingest_events WHERE batch_id=? AND processed",Integer.class,batchId));
    assertEquals(2,units());
    store.db.update("UPDATE ingest_batches SET lease_until=now()-interval '1 second' WHERE id=?",batchId);
    new Worker(store,tx,mail,true,"test@test.local").process();
    new Worker(store,tx,mail,true,"test@test.local").process();
    assertEquals("completed",result(receipt).get("status"));
    assertEquals(2,store.db.queryForObject("SELECT count(*) FROM objects WHERE project_id=?",Integer.class,project));
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM cost_entries WHERE project_id=?",Integer.class,project));
    assertEquals(2,units());
    assertEquals(2L,store.db.queryForObject("SELECT sum(units) FROM usage_ledger WHERE workspace_id=?",Long.class,workspace));
  }

  @Test
  void repairedQuarantinedBatchReplaysThroughHttpWithoutReservingAgain() throws Exception {
    var payload=event("trace","repaired-poison",1);var receipt=ingest.accept(project,batch(payload));
    Object batchId=receipt.get("batchId");
    store.db.update("UPDATE ingest_batches SET attempts=9,created_at=now()-interval '1 year' WHERE id=?",batchId);
    store.db.update("UPDATE ingest_events SET payload=jsonb_set(payload,'{startedAt}','\"invalid\"') WHERE batch_id=?",batchId);
    new Worker(store,tx,mail,true,"test@test.local").process();
    assertEquals("quarantined",result(receipt).get("status"));
    assertEquals(1,units());
    store.db.update("UPDATE ingest_events SET payload=?::jsonb WHERE batch_id=?",payload.toString(),batchId);
    String token=Support.token();
    store.db.update("INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1 hour')",Support.hash(token),user,"csrf-test");
    String path="/api/projects/"+project+"/batches/"+batchId+"/replay";
    mvc.perform(post(path).cookie(new jakarta.servlet.http.Cookie("mt_session",token)).header("X-CSRF-Token","csrf-test").contentType("application/json").content("{}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pending"));
    mvc.perform(post(path).cookie(new jakarta.servlet.http.Cookie("mt_session",token)).header("X-CSRF-Token","csrf-test").contentType("application/json").content("{}"))
        .andExpect(status().isConflict());
    assertEquals(1,units());
    new Worker(store,tx,mail,true,"test@test.local").process();
    assertEquals("completed",result(receipt).get("status"));
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM objects WHERE project_id=?",Integer.class,project));
    assertEquals(1L,store.db.queryForObject("SELECT sum(units) FROM usage_ledger WHERE workspace_id=?",Long.class,workspace));
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

  @Test void expiredPaidRetentionHasBoundedGraceAndDowngradesOnlyOnce() {
    store.db.update("UPDATE workspaces SET plan='Pro',subscription_status='active',period_end=now()-interval '2 days' WHERE id=?",workspace);
    SubscriptionRetention.reconcile(store,tx);
    var grace=store.db.queryForObject("SELECT retention_grace_until FROM workspaces WHERE id=?",java.sql.Timestamp.class,workspace);
    assertEquals("Pro",store.workspace(user).get("plan"));
    SubscriptionRetention.reconcile(store,tx);
    assertEquals(grace,store.db.queryForObject("SELECT retention_grace_until FROM workspaces WHERE id=?",java.sql.Timestamp.class,workspace));
    store.db.update("UPDATE workspaces SET retention_grace_until=now()-interval '1 second',used_units=123,auto_overage=true WHERE id=?",workspace);
    SubscriptionRetention.reconcile(store,tx);
    assertEquals("Free",store.workspace(user).get("plan"));
    assertEquals(0L,((Number)store.workspace(user).get("used_units")).longValue());
    assertEquals(false,store.workspace(user).get("auto_overage"));
    store.db.update("UPDATE workspaces SET used_units=7 WHERE id=?",workspace);
    SubscriptionRetention.reconcile(store,tx);
    assertEquals(7L,((Number)store.workspace(user).get("used_units")).longValue());
  }

  @Test void renewedSubscriptionSurvivesOldGraceAndGraceFreezesHistoricalCutoff() {
    store.db.update("UPDATE workspaces SET plan='Pro',subscription_status='canceled',period_end=now()+interval '15 days',retention_grace_until=now()+interval '20 days' WHERE id=?",workspace);
    long age=store.db.queryForObject("SELECT extract(epoch FROM now()-workspace_retention_cutoff(w))::bigint FROM workspaces w WHERE id=?",Long.class,workspace);
    assertTrue(age>=1105L*86400 && age<=1107L*86400);
    store.db.update("UPDATE workspaces SET subscription_status='active',retention_grace_until=NULL WHERE id=?",workspace);
    SubscriptionRetention.reconcile(store,tx);
    assertEquals("Pro",store.workspace(user).get("plan"));
    assertNull(store.workspace(user).get("retention_grace_until"));
  }

  @Test void cancellationRetriesDoNotExtendGraceAndRenewalClearsIt() {
    pack();
    var w=store.workspace(user);
    Instant start=((java.sql.Timestamp)w.get("period_start")).toInstant();
    Instant end=((java.sql.Timestamp)w.get("period_end")).toInstant();
    var event=renewalEvent(start,end);
    ((ObjectNode)event.path("data")).put("status","canceled");
    Instant occurred=Instant.now();
    event.put("occurred_at",occurred.toString());
    tx.executeWithoutResult(x->payments.applyEvent(event));
    var deadline=store.workspace(user).get("retention_grace_until");
    assertNotNull(deadline);
    assertEquals("Free",store.workspace(user).get("plan"));
    assertEquals("Core",store.workspace(user).get("retention_plan"));
    assertEquals(true,IngestionStatus.describe(store.workspace(user),0,false,false,Instant.now()).get("can_accept_new"));
    event.put("event_id","evt_"+UUID.randomUUID()).put("occurred_at",occurred.plusSeconds(1).toString());
    tx.executeWithoutResult(x->payments.applyEvent(event));
    assertEquals(deadline,store.workspace(user).get("retention_grace_until"));
    ((ObjectNode)event.path("data")).put("status","active");
    event.put("event_id","evt_"+UUID.randomUUID()).put("occurred_at",occurred.plusSeconds(2).toString());
    tx.executeWithoutResult(x->payments.applyEvent(event));
    assertNull(store.workspace(user).get("retention_grace_until"));
    assertEquals("Core",store.workspace(user).get("plan"));
  }

  @Test void endedSubscriptionUsesFreeQuotaWithoutResettingUsageAndNewCheckoutReplacesOnlyItsBoundSubscription() {
    pack();store.db.update("UPDATE workspaces SET subscription_status='canceled',used_units=49999 WHERE id=?",workspace);
    SubscriptionRetention.reconcile(store,tx);
    var w=store.workspace(user);assertEquals("Free",w.get("plan"));assertEquals(49999L,w.get("used_units"));
    assertEquals(1L,IngestionStatus.describe(w,0,false,false,Instant.now()).get("quota_remaining"));
    ingest.accept(project,batch(event("trace","free-last-unit",1)));
    assertThrows(ApiError.class,()->ingest.accept(project,batch(event("trace","free-no-extra",1))));
    UUID intent=UUID.randomUUID();String old=(String)w.get("subscription_id");
    store.db.update("INSERT INTO checkout_intents(id,workspace_id,plan,previous_subscription_id) VALUES(?,?,'Core',?)",intent,workspace,old);
    var renewal=renewalEvent(Instant.now().minusSeconds(1),Instant.now().plusSeconds(2592000));
    ((ObjectNode)renewal.path("data")).put("id","sub_new").putObject("custom_data").put("metertrail_intent",intent.toString());
    tx.executeWithoutResult(x->payments.applyEvent(renewal));
    assertEquals("Core",store.workspace(user).get("plan"));assertEquals("sub_new",store.workspace(user).get("subscription_id"));assertNull(store.workspace(user).get("retention_plan"));
    var delayed=renewalEvent(Instant.now(),Instant.now().plusSeconds(2592000));
    ((ObjectNode)delayed.path("data")).putObject("custom_data").put("metertrail_intent",intent.toString());
    tx.executeWithoutResult(x->payments.applyEvent(delayed));
    assertEquals("sub_new",store.workspace(user).get("subscription_id"));
  }

  @Test void gracePreservesPaidHistoryThenPurgesOnlyOldAnalytics() {
    store.db.update("UPDATE workspaces SET plan='Core',subscription_status='canceled',retention_grace_until=now()+interval '20 days' WHERE id=?",workspace);
    for (int age : List.of(10,95)) {
      store.db.update("INSERT INTO objects(project_id,type,object_id,revision,started_at,data) VALUES(?,'trace',?,1,now()-make_interval(days=>?),'{}')",project,"age-"+age,age);
      store.db.update("INSERT INTO cost_entries(project_id,object_id,revision,started_at,estimated_cost) VALUES(?,?,1,now()-make_interval(days=>?),0.01)",project,"age-"+age,age);
    }
    var worker=new Worker(store,tx,mail,true,"test@test.local");
    worker.retention();
    assertEquals(2,store.db.queryForObject("SELECT count(*) FROM objects WHERE project_id=?",Integer.class,project));
    store.db.update("UPDATE workspaces SET retention_grace_until=now()-interval '1 second' WHERE id=?",workspace);
    worker.retention();
    assertEquals(List.of("age-10"),store.db.queryForList("SELECT object_id FROM objects WHERE project_id=?",String.class,project));
    assertEquals(List.of("age-10"),store.db.queryForList("SELECT object_id FROM cost_entries WHERE project_id=?",String.class,project));
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM api_keys WHERE project_id=?",Integer.class,project));
  }

  @Test void billingListsPaginateBeyondOneHundredAndRejectInvalidPages() throws Exception {
    String token=Support.token();
    store.db.update("INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1 hour')",Support.hash(token),user,"billing-pages");
    var cookie=new jakarta.servlet.http.Cookie("mt_session",token);
    for(int i=0;i<107;i++) {
      store.db.update("INSERT INTO payments(id,workspace_id,status,amount,currency) VALUES(?,?,'completed',1,'USD')",workspace+"-"+i,workspace);
      store.db.update("INSERT INTO payment_adjustments(id,workspace_id,transaction_id,action,status,updated_at) VALUES(?,?,?,'refund','approved',now())",workspace+"-"+i,workspace,"txn-test");
      store.db.update("INSERT INTO usage_charges(id,workspace_id,subscription_id,period_start,period_end,kind,units,amount,status) VALUES(?,?,'sub-test',now(),now()+interval '1 month','addon',100000,8,'paid')",UUID.randomUUID(),workspace);
    }
    for(String kind:List.of("payments","adjustments")) {
      mvc.perform(get("/api/billing/history?kind="+kind+"&page=20&size=5").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(107)).andExpect(jsonPath("$.items.length()").value(5));
      mvc.perform(get("/api/billing/history?kind="+kind+"&page=21&size=5").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
    }
    mvc.perform(get("/api/billing/usage-charges?page=21&size=5").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(107)).andExpect(jsonPath("$.charges.length()").value(2));
    mvc.perform(get("/api/billing/history?kind=users").cookie(cookie)).andExpect(status().isBadRequest());
    mvc.perform(get("/api/billing/history?kind=payments&page=-1").cookie(cookie)).andExpect(status().isBadRequest());
    mvc.perform(get("/api/billing/usage-charges?size=101").cookie(cookie)).andExpect(status().isBadRequest());
    mvc.perform(get("/api/billing/history?kind=payments")).andExpect(status().isUnauthorized());
    store.db.update("DELETE FROM usage_charges WHERE workspace_id=?",workspace);
    store.db.update("DELETE FROM payment_adjustments WHERE workspace_id=?",workspace);
    store.db.update("DELETE FROM payments WHERE workspace_id=?",workspace);
  }

  @Test void analyticsCalendarFiltersMatchChartPaginationAndExport() throws Exception {
    String token=Support.token();
    store.db.update("INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1 hour')",Support.hash(token),user,"analytics-period");
    var cookie=new jakarta.servlet.http.Cookie("mt_session",token);
    store.db.update("UPDATE workspaces SET plan='Pro',subscription_status='active',period_end=now()+interval '1 month' WHERE id=?",workspace);
    int year=java.time.LocalDate.now(java.time.ZoneOffset.UTC).getYear();
    String[] dates={year+"-01-01T00:00:00Z",year+"-01-31T23:59:59Z",year+"-02-01T00:00:00Z",(year-1)+"-12-31T23:59:59Z"};
    for(int i=0;i<dates.length;i++) {
      String customer=i<2?"January-customer":i==2?"February-customer":"Previous-year";
      String data="{\"customerId\":\""+customer+"\",\"observationType\":\"generation\",\"usage\":{\"inputTokens\":100,\"outputTokens\":50}}";
      store.db.update("INSERT INTO objects(project_id,type,object_id,revision,started_at,data) VALUES(?,'observation',?,1,?,?::jsonb)",project,"calendar-"+i,java.sql.Timestamp.from(Instant.parse(dates[i])),data);
      if(i!=1)store.db.update("UPDATE objects SET cost=? WHERE project_id=? AND object_id=?",new java.math.BigDecimal(i==2?"0.0109":"0.0001"),project,"calendar-"+i);
    }
    String base="/api/reports/analytics?project="+project+"&year="+year;
    mvc.perform(get(base).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.chart[0].dimension").value("January-customer")).andExpect(jsonPath("$.chart[0].tokens").value(300));
    mvc.perform(get(base).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.chart_costs[0].dimension").value("February-customer")).andExpect(jsonPath("$.chart_costs[0].cost").value(0.0109)).andExpect(jsonPath("$.chart_costs[1].cost").value(0.0001));
    mvc.perform(get(base+"&month=1").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].objects").value(2)).andExpect(jsonPath("$.items[0].tokens").value(300));
    mvc.perform(get(base+"&month=2").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].dimension").value("February-customer"));
    mvc.perform(get(base+"&page=1").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.chart.length()").value(2));
    mvc.perform(get(base+"&month=13").cookie(cookie)).andExpect(status().isBadRequest());
    mvc.perform(get(base+"&dimension=userId").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].tokens").value(450));
    byte[] pdf=mvc.perform(get("/api/reports/analytics/export?project="+project+"&year="+year+"&month=1&format=pdf").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    try(var doc=org.apache.pdfbox.Loader.loadPDF(pdf)) {
      String text=new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
      assertTrue(text.contains("January-customer"));assertFalse(text.contains("February-customer"));assertFalse(text.contains("Previous-year"));
    }
  }

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

  @Test
  void firstKeyExemptionIsSerializedAndNeverResetByDeletion() throws Exception {
    String token = Support.token();
    store.db.update("INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1 hour')", Support.hash(token), user, "first-key");
    var cookie = new jakarta.servlet.http.Cookie("mt_session", token);
    UUID fresh = UUID.randomUUID();
    store.db.update("INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'First key')", fresh, workspace);
    String base = "/api/projects/" + fresh + "/keys";
    mvc.perform(get(base + "/creation-policy").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.verificationRequired").value(false));
    mvc.perform(post(base).cookie(cookie).contentType("application/json").content("{\"name\":\"first\"}")).andExpect(status().isForbidden());
    mvc.perform(post(base).cookie(cookie).header("X-CSRF-Token", "first-key").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    try {
      for (int i=0;i<2;i++) results.add(executor.submit(() -> {
        start.await();
        return mvc.perform(post(base).cookie(cookie).header("X-CSRF-Token", "first-key").contentType("application/json").content("{\"name\":\"first\"}")).andReturn().getResponse().getStatus();
      }));
      start.countDown();
      var statuses = new ArrayList<Integer>();
      for (var result: results) statuses.add(result.get(20, TimeUnit.SECONDS));
      Collections.sort(statuses);
      assertEquals(List.of(200,400), statuses);
    } finally { executor.shutdownNow(); }
    mvc.perform(get(base + "/creation-policy").cookie(cookie)).andExpect(jsonPath("$.verificationRequired").value(true));
    UUID id = store.db.queryForObject("SELECT id FROM api_keys WHERE project_id=?", UUID.class, fresh);
    mvc.perform(post(base + "/" + id + "/revoke").cookie(cookie).header("X-CSRF-Token","first-key").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
    store.db.update("UPDATE api_keys SET revoked_at=now(),deleted_at=now() WHERE id=?",id);
    mvc.perform(get(base + "/creation-policy").cookie(cookie)).andExpect(jsonPath("$.verificationRequired").value(true));
    mvc.perform(post(base).cookie(cookie).header("X-CSRF-Token","first-key").contentType("application/json").content("{\"name\":\"second\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"));
    mvc.perform(get("/api/projects/" + project + "/keys/creation-policy").cookie(cookie)).andExpect(jsonPath("$.verificationRequired").value(true));
    mvc.perform(get(base + "/creation-policy")).andExpect(status().isUnauthorized());
    assertEquals(1, store.db.queryForObject("SELECT count(*) FROM api_keys WHERE project_id=?", Integer.class, fresh));
  }

  @Test
  void deletedKeysAndRulesLeaveDataAndHistoryAndEnforceOwnership() throws Exception {
    String token = Support.token();
    store.db.update(
        "INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1"
            + " hour')",
        Support.hash(token),
        user,
        "csrf-test");
    var cookie = new jakarta.servlet.http.Cookie("mt_session", token);
    UUID keyId =
        store.db.queryForObject("SELECT id FROM api_keys WHERE project_id=?", UUID.class, project);
    String base = "/api/projects/" + project;
    String keyUrl = base + "/keys/" + keyId + "/delete";
    mvc.perform(post(keyUrl).cookie(cookie)).andExpect(status().isForbidden());
    mvc.perform(post(keyUrl).cookie(cookie).header("X-CSRF-Token", "csrf-test"))
        .andExpect(status().isConflict());
    ingest.accept(project, batch(event("trace", "preserved", 1)));
    new Worker(store, tx, mail, true, "test@test.local").process();
    long before = units();
    store.db.update("UPDATE api_keys SET revoked_at=now() WHERE id=?", keyId);
    UUID otherProject = UUID.randomUUID();
    store.db.update(
        "INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'Other')", otherProject, workspace);
    mvc.perform(
            post("/api/projects/" + otherProject + "/keys/" + keyId + "/delete")
                .cookie(cookie)
                .header("X-CSRF-Token", "csrf-test"))
        .andExpect(status().isNotFound());
    mvc.perform(post(keyUrl).cookie(cookie).header("X-CSRF-Token", "csrf-test"))
        .andExpect(status().isOk());
    mvc.perform(get(base + "/keys").cookie(cookie)).andExpect(jsonPath("$.length()").value(0));
    assertEquals(before, units());
    assertEquals(
        1,
        store.db.queryForObject(
            "SELECT count(*) FROM objects WHERE project_id=?", Integer.class, project));
    UUID rule = UUID.randomUUID();
    store.db.update(
        "INSERT INTO alert_rules(id,project_id,name,metric,threshold) VALUES(?,?,'Delete"
            + " me','latency_ms',1)",
        rule,
        project);
    store.db.update("INSERT INTO alert_events(rule_id,value) VALUES(?,150)", rule);
    mvc.perform(
            post(base + "/alerts/" + rule + "/delete")
                .cookie(cookie)
                .header("X-CSRF-Token", "csrf-test"))
        .andExpect(status().isOk());
    mvc.perform(get(base + "/alerts").cookie(cookie))
        .andExpect(jsonPath("$.rules.length()").value(0))
        .andExpect(jsonPath("$.events.length()").value(1));
    mvc.perform(
            post(base + "/alerts/" + rule)
                .cookie(cookie)
                .header("X-CSRF-Token", "csrf-test")
                .contentType("application/json")
                .content("{\"enabled\":true}"))
        .andExpect(status().isNotFound());
    new Worker(store, tx, mail, true, "test@test.local").alerts();
    assertEquals(
        1,
        store.db.queryForObject(
            "SELECT count(*) FROM alert_events WHERE rule_id=?", Integer.class, rule));
    assertEquals(
        2,
        store.db.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE workspace_id=? AND action IN"
                + " ('key.delete','alert.delete')",
            Integer.class,
            workspace));
  }

  @Test
  void activityPagesAndExportsIncludeAllRetainedRowsAndEnforceProjectOwnership() throws Exception {
    String token = Support.token();
    store.db.update(
        "INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1"
            + " hour')",
        Support.hash(token),
        user,
        "csrf-test");
    var cookie = new jakarta.servlet.http.Cookie("mt_session", token);
    for (int i = 0; i < 125; i++)
      store.db.update(
          "INSERT INTO objects(project_id,type,object_id,revision,started_at,data)"
              + " VALUES(?,'trace',?,1,now(),?::jsonb)",
          project,
          String.format("row-%03d", i),
          "{\"name\":\"中文活动 "
              + i
              + "\",\"status\":\"success\",\"userId\":\"u-"
              + i
              + "\",\"customerId\":\"c-"
              + i
              + "\",\"feature\":\"f-"
              + i
              + "\"}");
    store.db.update(
        "INSERT INTO objects(project_id,type,object_id,revision,started_at,data)"
            + " VALUES(?,'trace','expired',1,now()-interval '40 days','{}')",
        project);
    String base = "/api/projects/" + project + "/activity";
    mvc.perform(get(base).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(125))
        .andExpect(jsonPath("$.items.length()").value(5));
    mvc.perform(get(base + "?page=24").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(5))
        .andExpect(jsonPath("$.items[4].object_id").value("row-000"));
    mvc.perform(get(base + "?page=-1").cookie(cookie)).andExpect(status().isBadRequest());
    mvc.perform(get(base + "/export?format=csv").cookie(cookie)).andExpect(status().isBadRequest());
    byte[] excel =
        mvc.perform(get(base + "/export?format=xlsx&lang=zh-CN").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    String sheet = "";
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(excel))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.getName().equals("xl/worksheets/sheet1.xml"))
          sheet = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }
    }
    assertEquals(126, sheet.split("<row r=").length - 1);
    assertTrue(sheet.contains("中文活动 124"));
    assertFalse(sheet.contains("expired"));
    assertTrue(sheet.contains("成本 USD"));
    byte[] pdf =
        mvc.perform(get(base + "/export?format=pdf&lang=zh-CN").cookie(cookie))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
      assertTrue(doc.getNumberOfPages() > 1);
      String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
      assertTrue(text.contains("中文活动 124"));
      assertTrue(text.contains("成本 USD"));
      assertFalse(text.contains("expired"));
    }
    java.nio.file.Files.write(java.nio.file.Path.of("target/activity-export.xlsx"), excel);
    java.nio.file.Files.write(java.nio.file.Path.of("target/activity-export.pdf"), pdf);
    for (String kind : List.of("traces", "analytics", "audit")) {
      if (kind.equals("audit"))
        for (int i = 0; i < 125; i++)
          store.db.update(
              "INSERT INTO audit_logs(user_id,workspace_id,action,target,success)"
                  + " VALUES(?,?,'query.test',?,true)",
              user,
              workspace,
              "audit-" + i);
      String query = "/api/reports/" + kind + "?project=" + project;
      mvc.perform(get(query).cookie(cookie))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.total").value(125))
          .andExpect(jsonPath("$.items.length()").value(5));
      mvc.perform(get(query + "&page=24").cookie(cookie))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items.length()").value(5));
      for (int size : List.of(5, 10, 25, 50, 100)) {
        mvc.perform(get(query + "&size=" + size).cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.size").value(size))
            .andExpect(jsonPath("$.items.length()").value(size));
        mvc.perform(get(query + "&size=" + size + "&page=" + (124 / size)).cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(125 - (124 / size) * size));
      }
      mvc.perform(get(query + "&size=7").cookie(cookie)).andExpect(status().isBadRequest());
      for (String format : List.of("xlsx", "pdf")) {
        byte[] bytes =
            mvc.perform(
                    get("/api/reports/"
                            + kind
                            + "/export?project="
                            + project
                            + "&format="
                            + format
                            + "&lang=zh-CN")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        if (format.equals("xlsx")) {
          String xml = "";
          try (var zip =
              new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
              if (entry.getName().endsWith("sheet1.xml"))
                xml = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
          }
          assertEquals(126, xml.split("<row r=").length - 1);
          assertEquals(
              kind.equals("traces") ? 11 : 6,
              xml.substring(xml.indexOf("<row r="), xml.indexOf("</row>")).split("<c r=").length
                  - 1);
          if (kind.equals("traces")) {
            assertTrue(xml.contains("模型"));
            assertTrue(xml.contains("已知 Token"));
            assertTrue(xml.contains("已知估算费用 USD"));
            assertTrue(xml.contains("成本未知调用"));
          }
        } else
          try (var exported = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            assertTrue(exported.getNumberOfPages() > 1);
          }
      }
    }
    store.db.update("UPDATE objects SET data=jsonb_set(data,'{durationMs}','6000') WHERE project_id=? AND object_id='row-124'",project);
    String slowQuery="/api/reports/traces?project="+project+"&minDurationMs=5000&size=5";
    mvc.perform(get(slowQuery).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].object_id").value("row-124"));
    mvc.perform(get(slowQuery+"&page=1").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    mvc.perform(get(slowQuery.replace("5000","-1")).cookie(cookie)).andExpect(status().isBadRequest());
    for(String format:List.of("xlsx","pdf")) {
      byte[] slowExport=mvc.perform(get("/api/reports/traces/export?project="+project+"&minDurationMs=5000&format="+format).cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
      if(format.equals("pdf"))try(var doc=org.apache.pdfbox.Loader.loadPDF(slowExport)) {
        String text=new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        assertTrue(text.contains("row-124"));assertFalse(text.contains("row-123"));
      } else try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(slowExport))) {
        java.util.zip.ZipEntry entry;
        while((entry=zip.getNextEntry())!=null)if(entry.getName().endsWith("sheet1.xml")) {
          String xml=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
          assertEquals(2,xml.split("<row r=").length-1);assertTrue(xml.contains("row-124"));assertFalse(xml.contains("row-123"));
        }
      }
    }
    for (String dim : List.of("userId", "feature"))
      mvc.perform(
              get("/api/reports/analytics?project=" + project + "&dimension=" + dim).cookie(cookie))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.total").value(125));
    mvc.perform(get("/api/reports/traces?project=" + project + "&search=row-124").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
    byte[] filtered =
        mvc.perform(
                get("/api/reports/traces/export?project=" + project + "&search=row-124&format=pdf")
                    .cookie(cookie))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    try (var doc = org.apache.pdfbox.Loader.loadPDF(filtered)) {
      String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
      assertTrue(text.contains("row-124"));
      assertFalse(text.contains("row-123"));
    }
    mvc.perform(get("/api/workspace").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projects[0].created_at").exists());
    store.db.update(
        "UPDATE objects SET data=jsonb_set(data,'{customerId}','\"c-0\"') WHERE project_id=? AND"
            + " object_id='row-124'",
        project);
    var statistics =
        mvc.perform(get("/api/reports/analytics?project=" + project).cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].dimension").value("c-0"))
            .andExpect(jsonPath("$.items[0].objects").value(2))
            .andExpect(jsonPath("$.items[0].last_activity_at").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var latest =
        store.db.queryForObject(
            "SELECT started_at FROM objects WHERE project_id=? AND object_id='row-124'",
            java.sql.Timestamp.class,
            project);
    assertEquals(
        latest.toInstant().toEpochMilli(),
        java.time.Instant.parse(
                Support.parse(statistics).path("items").get(0).path("last_activity_at").asText())
            .toEpochMilli());
    UUID foreignUser = UUID.randomUUID(),
        foreignWorkspace = UUID.randomUUID(),
        foreignProject = UUID.randomUUID();
    store.db.update(
        "INSERT INTO users(id,email,verified) VALUES(?,?,true)",
        foreignUser,
        foreignUser + "@test.local");
    store.db.update(
        "INSERT INTO workspaces(id,owner_id,name) VALUES(?,?,'Foreign')",
        foreignWorkspace,
        foreignUser);
    store.db.update(
        "INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'Foreign')",
        foreignProject,
        foreignWorkspace);
    mvc.perform(get("/api/projects/" + foreignProject + "/activity").cookie(cookie))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/projects/" + foreignProject + "/activity/export?format=xlsx").cookie(cookie))
        .andExpect(status().isNotFound());
    for (String kind : List.of("traces", "analytics"))
      mvc.perform(
              get("/api/reports/" + kind + "/export?project=" + foreignProject + "&format=xlsx")
                  .cookie(cookie))
          .andExpect(status().isNotFound());
    mvc.perform(get("/api/reports/traces?project="+foreignProject+"&minDurationMs=5000").cookie(cookie)).andExpect(status().isNotFound());
    for(String format:List.of("xlsx","pdf"))mvc.perform(get("/api/reports/traces/export?project="+foreignProject+"&minDurationMs=5000&format="+format).cookie(cookie)).andExpect(status().isNotFound());
    String foreignToken = Support.token();
    store.db.update(
        "INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1"
            + " hour')",
        Support.hash(foreignToken),
        foreignUser,
        "foreign-csrf");
    mvc.perform(
            get("/api/reports/audit")
                .cookie(new jakarta.servlet.http.Cookie("mt_session", foreignToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void retentionFiltersQueriesExportsAndPurgesOnlyExpiredDataForEveryPlan() throws Exception {
    String token = Support.token();
    store.db.update("INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1 hour')", Support.hash(token), user, "retention-test");
    var cookie = new jakarta.servlet.http.Cookie("mt_session", token);
    for (String plan : List.of("Free", "Core", "Pro")) {
      store.db.update("UPDATE workspaces SET plan=? WHERE id=?", plan, workspace);
      int days = Billing.retention(plan);
      for (String name : List.of("retained", "expired")) {
        int age = name.equals("retained") ? days - 1 : days + 1;
        store.db.update("INSERT INTO objects(project_id,type,object_id,revision,started_at,data) VALUES(?,'trace',?,1,now()-make_interval(days=>?),'{}')", project, name, age);
        store.db.update("INSERT INTO cost_entries(project_id,object_id,revision,started_at,estimated_cost) VALUES(?,?,1,now()-make_interval(days=>?),0.01)", project, name, age);
      }
      String base = "/api/projects/" + project;
      mvc.perform(get(base + "/activity").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].object_id").value("retained"));
      mvc.perform(get(base + "/overview").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.summary.objects").value(1));
      byte[] pdf = mvc.perform(get(base + "/activity/export?format=pdf").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
      try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
        String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
        assertTrue(text.contains("retained"), plan);
        assertFalse(text.contains("expired"), plan);
      }
      new Worker(store, tx, mail, true, "test@test.local").retention();
      assertEquals(List.of("retained"), store.db.queryForList("SELECT object_id FROM objects WHERE project_id=?", String.class, project), plan);
      assertEquals(List.of("retained"), store.db.queryForList("SELECT object_id FROM cost_entries WHERE project_id=?", String.class, project), plan);
      store.db.update("DELETE FROM objects WHERE project_id=?", project);
      store.db.update("DELETE FROM cost_entries WHERE project_id=?", project);
    }
  }

  @Test
  void expiredIdentityCannotBeResurrectedAndCleanupPreservesBillingEvidence() {
    for (String plan : List.of("Free", "Core", "Pro")) {
      store.db.update("UPDATE workspaces SET plan=? WHERE id=?", plan, workspace);
      String name = "expired-" + plan;
      ingest.accept(project, batch(event("trace", name, 1)));
      new Worker(store, tx, mail, true, "test@test.local").process();
      int age = Billing.retention(plan) + 1;
      store.db.update("UPDATE billable_objects SET first_accepted_at=now()-make_interval(days=>?) WHERE project_id=? AND object_id=?", age, project, name);
      store.db.update("UPDATE objects SET started_at=now()-make_interval(days=>?) WHERE project_id=? AND object_id=?", age, project, name);
      long before = units();
      int ledger = store.db.queryForObject("SELECT count(*) FROM usage_ledger WHERE project_id=?", Integer.class, project);
      var rejected = result(ingest.accept(project, batch(event("trace", name, 2))));
      assertEquals(1, ((Number) rejected.get("rejected")).intValue());
      assertTrue(rejected.get("errors").toString().contains("OUTSIDE_RETENTION"));
      new Worker(store, tx, mail, true, "test@test.local").retention();
      assertEquals(0, store.db.queryForObject("SELECT count(*) FROM objects WHERE project_id=? AND object_id=?", Integer.class, project, name));
      assertEquals(1, store.db.queryForObject("SELECT count(*) FROM billable_objects WHERE project_id=? AND object_id=?", Integer.class, project, name));
      assertEquals(ledger, store.db.queryForObject("SELECT count(*) FROM usage_ledger WHERE project_id=?", Integer.class, project));
      assertEquals(before, units());
      assertTrue(result(ingest.accept(project, batch(event("trace", name, 2)))).get("errors").toString().contains("OUTSIDE_RETENTION"));
      assertEquals(before, units());
    }
  }

  @Test
  void fullDatabaseDumpRestoresEveryTableAndEncryptedMailWithoutChangingSource() throws Exception {
    ingest.accept(project, batch(event("trace", "backup-trace", 1)));
    new Worker(store, tx, mail, true, "test@test.local").process();
    store.mail("backup@example.test", "restore-drill", "encrypted restore evidence");
    var before = databaseFingerprints(store.db);
    String restored = "metertrail_restore_" + UUID.randomUUID().toString().replace("-", "");
    String dump = "/tmp/" + restored + ".dump";
    assertEquals(0, pg.execInContainer("pg_dump", "-U", pg.getUsername(), "-d", pg.getDatabaseName(), "-Fc", "-f", dump).getExitCode());
    assertEquals(0, pg.execInContainer("createdb", "-U", pg.getUsername(), restored).getExitCode());
    var restore = pg.execInContainer("pg_restore", "-U", pg.getUsername(), "-d", restored, "--exit-on-error", "--no-owner", dump);
    assertEquals(0, restore.getExitCode(), restore.getStderr());
    var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
        "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + restored,
        pg.getUsername(), pg.getPassword());
    var db = new org.springframework.jdbc.core.JdbcTemplate(source);
    assertEquals(before, databaseFingerprints(db));
    assertEquals(before, databaseFingerprints(store.db));
    assertEquals(Support.hash(key), db.queryForObject("SELECT key_hash FROM api_keys WHERE project_id=?", String.class, project));
    String body = db.queryForObject("SELECT body FROM outbox WHERE subject='restore-drill'", String.class);
    assertEquals("encrypted restore evidence", store.secrets.open(body));
    assertEquals(1L, db.queryForObject("SELECT used_units FROM workspaces WHERE id=?", Long.class, workspace));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM billable_objects WHERE project_id=?", Integer.class, project));
    // Restored sequences must permit writes, rather than colliding with existing outbox rows.
    db.update("INSERT INTO outbox(recipient,subject,body) VALUES('sequence@example.test','sequence','')");
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox WHERE subject='sequence'", Integer.class));
    assertEquals(0, pg.execInContainer("dropdb", "-U", pg.getUsername(), restored).getExitCode());
    assertEquals(0, pg.execInContainer("rm", dump).getExitCode());
  }

  Map<String,String> databaseFingerprints(org.springframework.jdbc.core.JdbcTemplate db) {
    var hashes = new TreeMap<String,String>();
    for (String table : db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
      assertTrue(table.matches("[a-z_]+"));
      hashes.put(table, db.queryForObject("SELECT md5(coalesce(string_agg(row_to_json(t)::text, E'\\n' ORDER BY row_to_json(t)::text),'')) FROM " + table + " t", String.class));
    }
    return hashes;
  }

  @Test
  void accumulatedBatchesDrainWithConcurrentWorkersWithoutLossOrDoubleBilling() throws Exception {
    var submitted = new ArrayList<Map<String,Object>>();
    long start = System.nanoTime();
    for (int batchIndex=0; batchIndex<12; batchIndex++) {
      JsonNode[] events = new JsonNode[100];
      for (int index=0; index<100; index++) events[index]=event("trace", "backlog-"+batchIndex+"-"+index, 1);
      var payload = batch(events);
      var receipt = ingest.accept(project, payload);
      submitted.add(receipt);
      assertEquals(100, result(receipt).get("accepted"));
      assertEquals(100, result(ingest.accept(project,payload)).get("duplicates"));
    }
    assertEquals(1200, units());
    assertEquals(0, store.db.queryForObject("SELECT count(*) FROM objects WHERE project_id=?", Integer.class, project));
    for (int round=0; round<4; round++) {
      var workers = new ArrayList<CompletableFuture<Void>>();
      for (int n=0; n<3; n++) workers.add(CompletableFuture.runAsync(()->new Worker(store,tx,mail,true,"test@test.local").process()));
      CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new)).get(30,TimeUnit.SECONDS);
    }
    for (var receipt : submitted) assertEquals("completed", result(receipt).get("status"));
    assertEquals(1200, store.db.queryForObject("SELECT count(*) FROM objects WHERE project_id=?", Integer.class, project));
    assertEquals(1200, store.db.queryForObject("SELECT count(*) FROM billable_objects WHERE project_id=?", Integer.class, project));
    assertEquals(1200L, store.db.queryForObject("SELECT sum(units) FROM usage_ledger WHERE project_id=?", Long.class, project));
    assertEquals(1200, units());
    assertEquals(0,store.db.queryForObject("SELECT count(*) FROM ingest_events WHERE project_id=? AND NOT processed",Integer.class,project));
    System.out.println("ISOLATED_BACKLOG_1200_ELAPSED_MS="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
  }

  @Test
  void maximumBatchOfFreshObjectsIsAcceptedAndRemainsIdempotent() {
    JsonNode[] events = new JsonNode[500];
    for (int index=0; index<events.length; index++) {
      events[index]=event("trace", "maximum-batch-"+index, 1);
    }
    var payload=batch(events);
    assertEquals(500, result(ingest.accept(project,payload)).get("accepted"));
    assertEquals(500, result(ingest.accept(project,payload)).get("duplicates"));
    assertEquals(500, units());
  }

  @Test
  void preparedMixedBatchPreservesIndicesConflictsAndRevisionBilling() {
    var original = event("trace", "prepared-existing", 1);
    ingest.accept(project, batch(original));
    var conflict = original.deepCopy();
    conflict.withObject("data").put("name", "different content");
    var invalid = event("trace", "prepared-invalid", 1);
    invalid.put("revision", 0);
    var revision = event("trace", "prepared-existing", 2);
    var fresh = event("trace", "prepared-new", 1);
    var receipt = ingest.accept(project, batch(original, invalid, conflict, revision, fresh));
    var row = result(receipt);
    assertEquals(2, row.get("accepted"));
    assertEquals(1, row.get("duplicates"));
    assertEquals(2, row.get("rejected"));
    JsonNode errors = Support.parse(row.get("errors").toString());
    assertEquals(1, errors.get(0).path("index").asInt());
    assertEquals("INVALID_REVISION", errors.get(0).path("code").asText());
    assertEquals(2, errors.get(1).path("index").asInt());
    assertEquals("EVENT_ID_CONFLICT", errors.get(1).path("code").asText());
    assertEquals(2, units());
    assertEquals(2, store.db.queryForObject(
        "SELECT count(*) FROM usage_ledger WHERE project_id=?", Integer.class, project));
    assertEquals(3, store.db.queryForObject(
        "SELECT event_index FROM ingest_events WHERE project_id=? AND event_id=?", Integer.class,
        project, UUID.fromString(revision.path("eventId").asText())));
  }

  @Test
  void invalidEventIdReasonsSurviveBatchProcessingWithOriginalIndices() {
    var missing = event("trace", "id-missing", 1); missing.remove("eventId");
    var wrongType = event("trace", "id-type", 1); wrongType.put("eventId", 123);
    var wrongLength = event("trace", "id-length", 1); wrongLength.put("eventId", "short");
    var wrongFormat = event("trace", "id-format", 1); wrongFormat.put("eventId", "x".repeat(37));
    var good = event("trace", "id-valid", 1);
    var row = result(ingest.accept(project, batch(missing, wrongType, wrongLength, wrongFormat, good)));
    assertEquals(1, row.get("accepted"));
    assertEquals(4, row.get("rejected"));
    JsonNode errors = Support.parse(row.get("errors").toString());
    String[] codes = {"EVENT_ID_REQUIRED", "INVALID_EVENT_ID_TYPE", "EVENT_ID_LENGTH_MUST_BE_36", "EVENT_ID_LENGTH_MUST_BE_36"};
    for (int i = 0; i < codes.length; i++) {
      assertEquals(i, errors.get(i).path("index").asInt());
      assertEquals(codes[i], errors.get(i).path("code").asText());
    }
    assertEquals(1, units());
    assertEquals(1, result(ingest.accept(project, batch(good))).get("duplicates"));
    assertEquals(1, units());
  }

  @Test
  void fallbackDoesNotRewriteUnconsumedAddonPacks() {
    UUID id = pack();
    packEvent(id, "completed", "800");
    var original = event("trace", "addon-unchanged", 1);
    ingest.accept(project, batch(original));
    String version = store.db.queryForObject(
        "SELECT xmin::text FROM usage_charges WHERE id=?", String.class, id);
    var receipt = ingest.accept(project, batch(original, event("trace", "addon-base-only", 1)));
    assertEquals(1, result(receipt).get("accepted"));
    assertEquals(1, result(receipt).get("duplicates"));
    assertEquals(version, store.db.queryForObject(
        "SELECT xmin::text FROM usage_charges WHERE id=?", String.class, id));
    assertEquals(0L, store.db.queryForObject(
        "SELECT consumed_units FROM usage_charges WHERE id=?", Long.class, id));
  }

  @Test
  void inactivePaidSubscriptionCannotUseFreshBatchShortcut() {
    pack();
    store.db.update("UPDATE workspaces SET subscription_status='canceled' WHERE id=?", workspace);
    long batches = store.db.queryForObject(
        "SELECT count(*) FROM ingest_batches WHERE project_id=?", Long.class, project);
    ApiError failure = assertThrows(ApiError.class,
        () -> ingest.accept(project, batch(event("trace", "canceled-fresh", 1))));
    assertEquals("QUOTA_EXCEEDED", failure.code);
    assertEquals(0, units());
    assertEquals(batches, store.db.queryForObject(
        "SELECT count(*) FROM ingest_batches WHERE project_id=?", Long.class, project));
    assertEquals(0L, store.db.queryForObject(
        "SELECT count(*) FROM ingest_events WHERE project_id=?", Long.class, project));
  }

  @Test
  void replayAndUnchargedRevisionDoNotRewriteWorkspaceUsage() {
    var original = event("trace", "workspace-unchanged", 1);
    ingest.accept(project, batch(original));
    String version = store.db.queryForObject(
        "SELECT xmin::text FROM workspaces WHERE id=?", String.class, workspace);
    assertEquals(1, result(ingest.accept(project, batch(original))).get("duplicates"));
    assertEquals(1, result(ingest.accept(project,
        batch(event("trace", "workspace-unchanged", 2)))).get("accepted"));
    assertEquals(version, store.db.queryForObject(
        "SELECT xmin::text FROM workspaces WHERE id=?", String.class, workspace));
    assertEquals(1, units());
  }

  @Autowired IngestAdmission admission;

  @Test
  void saturatedWriteAdmissionLeavesQuotaReadsAvailableAndNeverReserves() throws Exception {
    var leases = new ArrayList<IngestAdmission.Lease>();
    long batches = store.db.queryForObject(
        "SELECT count(*) FROM ingest_batches WHERE project_id=?", Long.class, project);
    try {
      for (int i = 0; i < admission.limit; i++) leases.add(admission.enter());
      mvc.perform(post("/v1/events/batch").header("Authorization", "Bearer " + key)
              .contentType("application/json").content(batch(event("trace", "admission-full", 1)).toString()))
          .andExpect(status().isServiceUnavailable())
          .andExpect(header().string("Retry-After", "1"))
          .andExpect(jsonPath("$.code").value("INGEST_BUSY"));
      mvc.perform(get("/v1/quota").header("Authorization", "Bearer " + key))
          .andExpect(status().isOk());
      assertEquals(0, units());
      assertEquals(batches, store.db.queryForObject(
          "SELECT count(*) FROM ingest_batches WHERE project_id=?", Long.class, project));
    } finally {
      leases.forEach(IngestAdmission.Lease::close);
    }
    mvc.perform(post("/v1/events/batch").header("Authorization", "Bearer " + key)
            .contentType("application/json").content(batch(event("trace", "admission-recovered", 1)).toString()))
        .andExpect(status().isAccepted());
    assertEquals(1, units());
  }

  @Test
  void malformedRequestsReleaseTheirWriteAdmission() throws Exception {
    for (int i = 0; i <= admission.limit; i++)
      mvc.perform(post("/v1/events/batch").header("Authorization", "Bearer " + key)
              .contentType("application/json").content("{"))
          .andExpect(status().isBadRequest());
    assertEquals(admission.limit, admission.permits.availablePermits());
    assertEquals(0, units());
  }

  @Test
  void fallbackHttpAlsoRejectsMalformedEventsBeforeBatchCreation() throws Exception {
    var invalid = event("trace", "sync-invalid", 1); invalid.put("revision", 0);
    mvc.perform(post("/v1/events/batch").header("Authorization", "Bearer " + key)
        .contentType("application/json").content(batch(event("trace", "sync-good", 1), invalid).toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REVISION"))
        .andExpect(jsonPath("$.errors[0].index").value(1))
        .andExpect(jsonPath("$.errors[0].field").value("events[1].revision"));
    assertEquals(0, units());
    assertEquals(0L, store.db.queryForObject("SELECT count(*) FROM ingest_batches WHERE project_id=?", Long.class, project));
    assertEquals(admission.limit, admission.permits.availablePermits());
  }

  @Test
  void omittedRevisionAndExplicitOneAreTheSameIdentityAndNewIdsNeedNotBeUuids() throws Exception {
    var e = event("trace", "optional-identity", 1);
    e.put("eventId", "a".repeat(36)); e.remove("revision");
    var b = batch(e); b.remove("schemaVersion"); b.remove("agent");
    mvc.perform(post("/v1/events/batch").header("Authorization", "Bearer " + key)
        .contentType("application/json").content(b.toString())).andExpect(status().isAccepted());
    assertEquals(1, units());
    assertEquals(1L, store.db.queryForObject("SELECT revision FROM billable_objects WHERE project_id=? AND object_id='optional-identity'", Long.class, project));
    e.put("revision", 1);
    assertEquals(1, result(ingest.accept(project, batch(e))).get("duplicates"));
    assertEquals(1, units());
    var conflict = e.deepCopy(); conflict.withObject("data").put("durationMs", 999);
    assertTrue(result(ingest.accept(project, batch(conflict))).get("errors").toString().contains("EVENT_ID_CONFLICT"));
    conflict.put("eventId", "b".repeat(36)); conflict.put("revision", 2);
    assertEquals(1, result(ingest.accept(project, batch(conflict))).get("accepted"));
    assertEquals(1, units());
    assertEquals(2L, store.db.queryForObject("SELECT revision FROM billable_objects WHERE project_id=? AND object_id='optional-identity'", Long.class, project));
  }

  @Test
  void legacyUuidWritesStillFillTextIdentityDuringRollingDeployment() {
    var receipt = ingest.accept(project, batch(event("trace", "legacy-batch", 1)));
    var legacy = event("trace", "legacy-writer", 1);
    legacy.put("eventId", legacy.path("eventId").asText().toUpperCase(Locale.ROOT));
    var p = ingest.prepare(batch(legacy)).get(0);
    store.db.update("INSERT INTO ingest_events(project_id,event_id,content_hash,batch_id,event_index,payload,processed) VALUES(?,?,?,?,?,?::jsonb,true)",
        project, UUID.fromString(legacy.path("eventId").asText()), p.eventHash(), UUID.fromString(receipt.get("batchId").toString()), 0, p.json());
    assertEquals(legacy.path("eventId").asText().toLowerCase(Locale.ROOT),
        store.db.queryForObject("SELECT event_key FROM ingest_events WHERE project_id=? AND event_id=?", String.class, project, UUID.fromString(legacy.path("eventId").asText())));
    assertEquals(1, result(ingest.accept(project, batch(legacy))).get("duplicates"));
    assertEquals(1, units());
  }

  @Test
  void busyWorkerSliceDrainsMoreThanTenBatchesWithoutRebilling() {
    var receipts = new ArrayList<Map<String,Object>>();
    for (int i = 0; i < 25; i++)
      receipts.add(ingest.accept(project, batch(event("trace", "busy-slice-" + i, 1))));
    var worker = new Worker(store, tx, mail, true, "test@test.local");
    assertFalse(worker.drain(1000, TimeUnit.SECONDS.toNanos(10)));
    for (var receipt : receipts) assertEquals("completed", result(receipt).get("status"));
    assertFalse(worker.drain(1000, TimeUnit.SECONDS.toNanos(10)));
    assertEquals(25, units());
    assertEquals(25, store.db.queryForObject(
        "SELECT count(*) FROM objects WHERE project_id=?", Integer.class, project));
    assertEquals(25L, store.db.queryForObject(
        "SELECT sum(units) FROM usage_ledger WHERE project_id=?", Long.class, project));
  }

  @Test void emailChangeRevokesAllOldSessionsAndOnlyNewEmailCanLogIn() throws Exception {
    String oldEmail=user+"@test.local",newEmail=user+"@new.test",password="synthetic-password-12345";
    store.db.update("UPDATE users SET password_hash=? WHERE id=?",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(password),user);
    var cookies=new ArrayList<jakarta.servlet.http.Cookie>();
    for(int i=0;i<2;i++){String token=Support.token();store.db.update("INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1 hour')",Support.hash(token),user,"csrf-email");cookies.add(new jakarta.servlet.http.Cookie("mt_session",token));}
    UUID oldChallenge=challenges.issue(user,"email.old",newEmail,null);
    String oldCode=syntheticMailCode(oldEmail);
    store.db.update("UPDATE verification_challenges SET created_at=now()-interval '61 seconds' WHERE id=?",oldChallenge);
    UUID newChallenge=challenges.issue(user,"email.new",newEmail,null);
    String newCode=syntheticMailCode(newEmail);
    var body=new ObjectMapper().valueToTree(Map.of("email",newEmail,"oldChallengeId",oldChallenge,"oldCode",oldCode,"newChallengeId",newChallenge,"newCode",newCode));
    mvc.perform(post("/api/account/email").cookie(cookies.get(0)).header("X-CSRF-Token","csrf-email").contentType("application/json").content(body.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("sign_in_required"));
    assertEquals(0,store.db.queryForObject("SELECT count(*) FROM sessions WHERE user_id=?",Integer.class,user));
    for(var cookie:cookies)mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/login").contentType("application/json").content(new ObjectMapper().writeValueAsString(Map.of("email",oldEmail,"password",password)))).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/login").contentType("application/json").content(new ObjectMapper().writeValueAsString(Map.of("email",newEmail,"password",password)))).andExpect(status().isOk());
    assertEquals(workspace,store.workspace(user).get("id"));assertEquals(1,store.db.queryForObject("SELECT count(*) FROM projects WHERE workspace_id=?",Integer.class,workspace));
  }

  String syntheticMailCode(String recipient) {
    String body=store.secrets.open(store.db.queryForObject("SELECT body FROM outbox WHERE recipient=? ORDER BY id DESC LIMIT 1",String.class,recipient));
    return body.substring(body.indexOf(": ")+2,body.indexOf(": ")+8);
  }

  @Test void passwordResetRevokesEverySessionAndOldPasswordStopsWorking() throws Exception {
    String email=user+"@test.local",oldPassword="old-synthetic-password-12345",newPassword="new-synthetic-password-67890";
    store.db.update("UPDATE users SET password_hash=? WHERE id=?",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(oldPassword),user);
    var cookies=new ArrayList<jakarta.servlet.http.Cookie>();
    for(int i=0;i<2;i++){String token=Support.token();store.db.update("INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1 hour')",Support.hash(token),user,"csrf-reset");cookies.add(new jakarta.servlet.http.Cookie("mt_session",token));}
    UUID challenge=challenges.issue(user,"reset-password",email,null);
    var body=new ObjectMapper().writeValueAsString(Map.of("email",email,"password",newPassword,"challengeId",challenge,"code",syntheticMailCode(email)));
    mvc.perform(post("/api/auth/reset").contentType("application/json").content(body)).andExpect(status().isOk());
    for(var cookie:cookies)mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/login").contentType("application/json").content(new ObjectMapper().writeValueAsString(Map.of("email",email,"password",oldPassword)))).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/login").contentType("application/json").content(new ObjectMapper().writeValueAsString(Map.of("email",email,"password",newPassword)))).andExpect(status().isOk());
    mvc.perform(post("/api/auth/reset").contentType("application/json").content(body)).andExpect(status().isBadRequest());
  }

  @Test void githubIdentityConflictsAndFirstRegistrationAreHandledWithoutAccountMerging() throws Exception {
    for(String mode:List.of("already-linked","existing-email","unverified-email","new-account")) {
      String github="synthetic-"+UUID.randomUUID(),email="oauth-"+UUID.randomUUID()+"@test.local";
      UUID other=UUID.randomUUID();
      if(mode.equals("already-linked"))store.db.update("INSERT INTO users(id,email,verified,github_id) VALUES(?,?,true,?)",other,email,github);
      if(mode.equals("existing-email"))email=user+"@test.local";
      var builder=org.springframework.web.client.RestClient.builder();
      var provider=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
      provider.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://github.com/login/oauth/access_token")).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"access_token\":\"synthetic-token\"}",org.springframework.http.MediaType.APPLICATION_JSON));
      provider.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.github.com/user")).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"id\":\""+github+"\"}",org.springframework.http.MediaType.APPLICATION_JSON));
      provider.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.github.com/user/emails")).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(new ObjectMapper().writeValueAsString(List.of(Map.of("email",email,"primary",true,"verified",!mode.equals("unverified-email")))),org.springframework.http.MediaType.APPLICATION_JSON));
      var client=builder.build();
      var redis=org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
      @SuppressWarnings("unchecked") org.springframework.data.redis.core.ValueOperations<String,String> values=org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
      org.mockito.Mockito.when(redis.opsForValue()).thenReturn(values);
      org.mockito.Mockito.when(values.getAndDelete("oauth:synthetic-state")).thenReturn(mode.equals("already-linked")?user.toString():"login",(String)null);
      var auth=new AuthController(store,challenges,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4),rates,false);
      var controller=new GithubController(store,auth,challenges,redis,tx,"synthetic-client","synthetic-secret","https://example.test");
      var request=new org.springframework.mock.web.MockHttpServletRequest();request.setCookies(new jakarta.servlet.http.Cookie("mt_oauth","synthetic-state"));
      var response=new org.springframework.mock.web.MockHttpServletResponse();
      int before=store.db.queryForObject("SELECT count(*) FROM users",Integer.class);
      try(var factory=org.mockito.Mockito.mockStatic(org.springframework.web.client.RestClient.class)) {
        factory.when(org.springframework.web.client.RestClient::create).thenReturn(client);
        if(mode.equals("new-account")) {
          controller.callback("synthetic-code","synthetic-state","",request,response);
          assertEquals(before+1,store.db.queryForObject("SELECT count(*) FROM users",Integer.class));
          UUID registered=store.db.queryForObject("SELECT id FROM users WHERE github_id=?",UUID.class,github);
          assertEquals(1,store.db.queryForObject("SELECT count(*) FROM workspaces WHERE owner_id=?",Integer.class,registered));
          assertEquals("https://example.test/app/workspace",response.getRedirectedUrl());
          assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(c->c.startsWith("mt_session=")));
        } else {
          ApiError error=assertThrows(ApiError.class,()->controller.callback("synthetic-code","synthetic-state","",request,response));
          assertEquals(mode.equals("already-linked")?"GITHUB_ALREADY_LINKED":mode.equals("existing-email")?"LOGIN_AND_LINK_EXISTING_ACCOUNT":"GITHUB_VERIFIED_EMAIL_REQUIRED",error.code);
          assertEquals(before,store.db.queryForObject("SELECT count(*) FROM users",Integer.class));
          assertFalse(response.getHeaders("Set-Cookie").stream().anyMatch(c->c.startsWith("mt_session=")));
          assertNull(store.db.queryForObject("SELECT github_id FROM users WHERE id=?",String.class,user));
        }
        assertThrows(ApiError.class,()->controller.callback("synthetic-code","synthetic-state","",request,new org.springframework.mock.web.MockHttpServletResponse()));
      }
      provider.verify();
    }
  }

  @Test void forciblyKilledWorkerJvmRollsBackAndFreshJvmCompletesReservedBatchOnce() throws Exception {
    String suffix=UUID.randomUUID().toString().replace("-", ""),function="jvm_fault_"+suffix,application="worker-drill-"+suffix;
    var receipt=ingest.accept(project,batch(event("observation","jvm-first",1),event("trace","jvm-second",1)));
    store.db.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+project+"' AND NEW.object_id='jvm-second' THEN PERFORM pg_sleep(3); END IF; RETURN NEW; END $$");
    store.db.execute("CREATE TRIGGER "+function+" BEFORE INSERT ON objects FOR EACH ROW EXECUTE FUNCTION "+function+"()");
    Process killed=null;
    try {
      killed=startWorkerJvm(application);
      boolean entered=false;
      for(int attempt=0;attempt<150;attempt++) {
        entered=store.db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name=? AND wait_event='PgSleep')",Boolean.class,application);
        if(entered)break;
        if(!killed.isAlive())fail("Worker exited before reaching the injected delay");
        Thread.sleep(100);
      }
      assertTrue(entered,"Worker must reach the second object inside the real transaction");
      killed.destroyForcibly();assertTrue(killed.waitFor(10,TimeUnit.SECONDS));
      boolean disconnected=false;
      for(int attempt=0;attempt<100;attempt++) {
        disconnected=!store.db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name=?)",Boolean.class,application);
        if(disconnected)break;Thread.sleep(100);
      }
      assertTrue(disconnected);
      assertEquals(0,store.db.queryForObject("SELECT count(*) FROM objects WHERE project_id=?",Integer.class,project));
      assertEquals(0,store.db.queryForObject("SELECT count(*) FROM cost_entries WHERE project_id=?",Integer.class,project));
      assertEquals("pending",result(receipt).get("status"));assertEquals(2,units());
      assertEquals(2,store.db.queryForObject("SELECT count(*) FROM ingest_events WHERE batch_id=? AND NOT processed",Integer.class,receipt.get("batchId")));
    } finally {
      if(killed!=null&&killed.isAlive())killed.destroyForcibly();
      store.db.execute("DROP TRIGGER "+function+" ON objects");store.db.execute("DROP FUNCTION "+function+"()");
    }
    var recovered=startWorkerJvm(application+"-restart");
    assertTrue(recovered.waitFor(20,TimeUnit.SECONDS));assertEquals(0,recovered.exitValue());
    assertEquals("completed",result(receipt).get("status"));
    assertEquals(2,store.db.queryForObject("SELECT count(*) FROM objects WHERE project_id=?",Integer.class,project));
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM cost_entries WHERE project_id=?",Integer.class,project));
    assertEquals(2,units());
    var repeated=startWorkerJvm(application+"-again");assertTrue(repeated.waitFor(20,TimeUnit.SECONDS));assertEquals(0,repeated.exitValue());assertEquals(2,units());
    assertEquals(2L,store.db.queryForObject("SELECT sum(units) FROM usage_ledger WHERE project_id=?",Long.class,project));
  }

  Process startWorkerJvm(String application) throws Exception {
    var process=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),WorkerProcessDrill.class.getName());
    process.environment().put("DRILL_JDBC_URL",pg.getJdbcUrl()+(pg.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName="+application);
    process.environment().put("DRILL_DB_USER",pg.getUsername());process.environment().put("DRILL_DB_PASSWORD",pg.getPassword());
    process.redirectErrorStream(true);process.redirectOutput(java.nio.file.Path.of("target",application+".log").toFile());
    return process.start();
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
  void immediatePasswordCodeResendCannotQueueAnotherEmailOrInvalidateFirstCode() {
    String email=user+"@test.local";
    UUID first=challenges.issue(user,"reset-password",email,null);
    var before=store.db.queryForList("SELECT * FROM outbox WHERE recipient=?",email);
    assertEquals(1,before.size());
    assertThrows(ApiError.class,()->challenges.issue(user,"reset-password",email,null));
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM outbox WHERE recipient=?",Integer.class,email));
    assertEquals(false,store.db.queryForObject("SELECT consumed FROM verification_challenges WHERE id=?",Boolean.class,first));
    String body=store.secrets.open((String)before.get(0).get("body"));
    String code=body.substring(body.indexOf(": ")+2,body.indexOf(": ")+8);
    challenges.consume(user,"reset-password",email,first.toString(),code,null);
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
  void receiptIsHttp202WithPendingAndQueryRemainsExplicit() throws Exception {
    var response = mvc.perform(post("/v1/events/batch")
        .header("Authorization", "Bearer " + key)
        .contentType("application/json")
        .content(batch(event("trace", "http-receipt", 1)).toString()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("pending"))
        .andExpect(jsonPath("$.received").value(1))
        .andExpect(jsonPath("$.batchId").isNotEmpty())
        .andExpect(jsonPath("$.accepted").doesNotExist())
        .andReturn().getResponse();
    String batchId = Support.parse(response.getContentAsString()).path("batchId").asText();
    mvc.perform(get("/v1/batches/" + batchId).header("Authorization", "Bearer " + key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("pending"));
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
