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
      for (int size : List.of(5, 10, 50, 100)) {
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
              kind.equals("traces") ? 7 : 6,
              xml.substring(xml.indexOf("<row r="), xml.indexOf("</row>")).split("<c r=").length
                  - 1);
        } else
          try (var exported = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            assertTrue(exported.getNumberOfPages() > 1);
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
  void receiptIsHttp200WithPendingAndQueryRemainsExplicit() throws Exception {
    var response = mvc.perform(post("/v1/events/batch")
        .header("Authorization", "Bearer " + key)
        .contentType("application/json")
        .content(batch(event("trace", "http-receipt", 1)).toString()))
        .andExpect(status().isOk())
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
