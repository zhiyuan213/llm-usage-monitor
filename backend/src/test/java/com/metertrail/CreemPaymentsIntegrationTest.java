package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "metertrail.secret=test-secret-at-least-32-characters-long",
      "metertrail.worker-enabled=false",
      "metertrail.creem-secret=synthetic-creem-webhook-secret",
      "metertrail.creem-core-product=prod_CoreTest",
      "metertrail.creem-pro-product=prod_ProTest",
      "metertrail.creem-url=https://api.creem.io",
      "metertrail.ingest.pipeline.enabled=false",
      "metertrail.secure-cookies=false"
    })
@AutoConfigureMockMvc
@Testcontainers
class CreemPaymentsIntegrationTest {
  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", pg::getJdbcUrl);
    r.add("spring.datasource.username", pg::getUsername);
    r.add("spring.datasource.password", pg::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired Store store;
  @MockBean RateLimits limits;
  UUID owner, other, admin;
  Cookie userCookie, otherCookie, adminCookie;
  final ObjectMapper json = new ObjectMapper();
  final String csrf = "support-test-csrf";

  Cookie account(UUID id, boolean role) {
    store.db.update(
        "INSERT INTO users(id,email,verified,is_admin) VALUES(?,?,true,?)",
        id,
        id + "@test.local",
        role);
    store.db.update(
        "INSERT INTO workspaces(id,owner_id,name) VALUES(?,?,'test')", UUID.randomUUID(), id);
    String token = Support.token();
    store.db.update(
        "INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1"
            + " hour')",
        Support.hash(token),
        id,
        csrf);
    return new Cookie("mt_session", token);
  }

  @BeforeEach
  void setup() {
    owner = UUID.randomUUID();
    other = UUID.randomUUID();
    admin = UUID.randomUUID();
    userCookie = account(owner, false);
    otherCookie = account(other, false);
    adminCookie = account(admin, true);
  }


  final String webhookSecret="synthetic-creem-webhook-secret";
  UUID intent,workspace;
  String sub;
  java.time.Instant start,end;

  void billingFixture() {
    workspace=store.db.queryForObject("SELECT id FROM workspaces WHERE owner_id=?",UUID.class,owner);
    intent=UUID.randomUUID();sub="sub_"+UUID.randomUUID().toString().replace("-","");
    start=java.time.Instant.now().minusSeconds(60);end=start.plusSeconds(2592000);
    store.db.update("INSERT INTO checkout_intents(id,workspace_id,plan,payment_provider) VALUES(?,?,'Core','creem')",intent,workspace);
  }
  com.fasterxml.jackson.databind.node.ObjectNode event(String type,java.time.Instant at) {
    var root=json.createObjectNode();root.put("id","evt_"+UUID.randomUUID());root.put("eventType",type);root.put("created_at",at.toEpochMilli());
    var object=root.putObject("object");object.put("id",sub);object.put("mode","prod");object.put("status","active");
    object.putObject("product").put("id","prod_CoreTest");object.putObject("customer").put("id","cust_Test");
    object.put("current_period_start_date",start.toString());object.put("current_period_end_date",end.toString());
    object.putObject("metadata").put("metertrail_intent",intent.toString());return root;
  }
  org.springframework.test.web.servlet.ResultActions send(JsonNode event) throws Exception {
    String body=json.writeValueAsString(event);
    return mvc.perform(post("/webhooks/creem").header("creem-signature",Support.hmac(webhookSecret,body)).contentType("application/json").content(body));
  }
  @Test void rejectsUnsignedAndWrongEnvironmentWithoutMutatingState() throws Exception {
    billingFixture();var e=event("subscription.paid",java.time.Instant.now());
    mvc.perform(post("/webhooks/creem").contentType("application/json").content(e.toString())).andExpect(status().isUnauthorized());
    ((com.fasterxml.jackson.databind.node.ObjectNode)e.path("object")).put("mode","test");
    send(e).andExpect(status().isBadRequest());
    assertEquals("Free",store.db.queryForObject("SELECT plan FROM workspaces WHERE id=?",String.class,workspace));
  }
  @Test void paidEventsAreIdempotentAndPreserveUsageUntilTheNextPeriod() throws Exception {
    billingFixture();var at=java.time.Instant.now();var paid=event("subscription.paid",at);
    send(paid).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("processed"));
    store.db.update("UPDATE workspaces SET used_units=17 WHERE id=?",workspace);
    send(paid).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));
    send(event("subscription.paid",at.plusMillis(10))).andExpect(status().isOk());
    assertEquals(17L,store.db.queryForObject("SELECT used_units FROM workspaces WHERE id=?",Long.class,workspace));
    var changed=paid.deepCopy();changed.with("object").put("status","past_due");send(changed).andExpect(status().isConflict());
    start=end;end=start.plusSeconds(2592000);send(event("subscription.paid",at.plusMillis(20))).andExpect(status().isOk());
    assertEquals(0L,store.db.queryForObject("SELECT used_units FROM workspaces WHERE id=?",Long.class,workspace));
    assertEquals("creem",store.db.queryForObject("SELECT payment_provider FROM workspaces WHERE id=?",String.class,workspace));
  }
  @Test void scheduledCancellationKeepsAccessButPastDueRequiresAnotherPaidEvent() throws Exception {
    billingFixture();var at=java.time.Instant.now();send(event("subscription.paid",at)).andExpect(status().isOk());
    var cancel=event("subscription.scheduled_cancel",at.plusMillis(10));cancel.with("object").put("status","scheduled_cancel");
    send(cancel).andExpect(status().isOk());
    assertEquals("active",store.db.queryForObject("SELECT subscription_status FROM workspaces WHERE id=?",String.class,workspace));
    var late=event("subscription.past_due",at.plusMillis(20));late.with("object").put("status","past_due");send(late).andExpect(status().isOk());
    send(event("subscription.active",at.plusMillis(30))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("awaiting_payment"));
    assertEquals("past_due",store.db.queryForObject("SELECT subscription_status FROM workspaces WHERE id=?",String.class,workspace));
    send(event("subscription.paid",at.plusMillis(15))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("stale"));
    send(event("subscription.paid",at.plusMillis(40))).andExpect(status().isOk());
  }
  @Test void cannotReplaceALegacySubscriptionOrActivateAnUnknownProduct() throws Exception {
    billingFixture();store.db.update("UPDATE workspaces SET subscription_id='sub_legacy' WHERE id=?",workspace);
    send(event("subscription.paid",java.time.Instant.now())).andExpect(status().isConflict());
    store.db.update("UPDATE workspaces SET subscription_id=NULL WHERE id=?",workspace);
    var invalid=event("subscription.paid",java.time.Instant.now());invalid.with("object").with("product").put("id","prod_Other");
    send(invalid).andExpect(status().isBadRequest());
    assertEquals("Free",store.db.queryForObject("SELECT plan FROM workspaces WHERE id=?",String.class,workspace));
  }
  @Test void replacementPaymentReactivatesCanceledSubscriptionButOldIntentCannotReplaceItAgain() throws Exception {
    billingFixture();String oldSub="sub_previous";
    store.db.update("UPDATE workspaces SET plan='Core',payment_provider='creem',subscription_id=?,subscription_status='canceled',used_units=17,retention_grace_until=now()+interval '30 days' WHERE id=?",oldSub,workspace);
    store.db.update("UPDATE checkout_intents SET previous_subscription_id=? WHERE id=?",oldSub,intent);
    start=java.time.Instant.now().minusSeconds(1);end=start.plusSeconds(2592000);
    var at=java.time.Instant.now();send(event("subscription.paid",at)).andExpect(status().isOk());
    var w=store.workspace(owner);assertEquals(sub,w.get("subscription_id"));assertEquals("active",w.get("subscription_status"));assertNull(w.get("retention_grace_until"));
    var ingestion=IngestionStatus.describe(w,0,false,false,java.time.Instant.now());
    assertEquals(true,ingestion.get("can_accept_new"));
    String newSub=sub;
    // Even after the new subscription ends, a delayed old checkout cannot take it over.
    store.db.update("UPDATE workspaces SET subscription_status='canceled' WHERE id=?",workspace);
    sub="sub_unrelated";send(event("subscription.paid",at.plusMillis(10))).andExpect(status().isConflict());
    assertEquals(newSub,store.workspace(owner).get("subscription_id"));
  }
  @Test void unrelatedDashboardTestEventsAreAcknowledgedWithoutGrantingAccess() throws Exception {
    billingFixture();var e=event("checkout.completed",java.time.Instant.now());var o=e.with("object");o.put("id","ch_DashboardTest");o.put("status","completed");o.remove("metadata");
    o.putObject("subscription").put("id","sub_Unknown");o.putObject("order").put("status","paid");
    send(e).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ignored"));
    assertEquals("Free",store.db.queryForObject("SELECT plan FROM workspaces WHERE id=?",String.class,workspace));
  }

  @Test void recordsRefundsAndCanceledSubscriptionsWithoutDeletingProjectData() throws Exception {
    billingFixture();var at=java.time.Instant.now();var paid=event("subscription.paid",at);
    paid.with("object").put("last_transaction_id","tran_Initial");
    paid.with("object").putObject("last_transaction").put("amount",2900).put("amount_paid",3100).put("currency","USD");
    send(paid).andExpect(status().isOk());
    assertEquals(new java.math.BigDecimal("31.00"),store.db.queryForObject("SELECT amount FROM payments WHERE workspace_id=?",java.math.BigDecimal.class,workspace));
    var refund=event("refund.created",at.plusMillis(10));var o=refund.with("object");o.remove("metadata");
    o.put("id","ref_Test");o.put("status","succeeded");o.put("refund_amount",100);o.put("refund_currency","USD");
    o.putObject("subscription").put("id",sub);o.putObject("transaction").put("id","tran_Initial");
    send(refund).andExpect(status().isOk());send(refund).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));
    assertEquals(1L,store.db.queryForObject("SELECT count(*) FROM payment_adjustments WHERE workspace_id=?",Long.class,workspace));
    var cancelled=event("subscription.canceled",at.plusMillis(20));cancelled.with("object").put("status","canceled");send(cancelled).andExpect(status().isOk());
    assertEquals("canceled",store.db.queryForObject("SELECT subscription_status FROM workspaces WHERE id=?",String.class,workspace));
    assertEquals("Free",store.db.queryForObject("SELECT plan FROM workspaces WHERE id=?",String.class,workspace));
    assertEquals("Core",store.db.queryForObject("SELECT retention_plan FROM workspaces WHERE id=?",String.class,workspace));
    assertNotNull(store.db.queryForObject("SELECT retention_grace_until FROM workspaces WHERE id=?",java.sql.Timestamp.class,workspace));
  }
}
