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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "metertrail.secret=test-secret-at-least-32-characters-long",
      "metertrail.worker-enabled=false",
      "metertrail.ingest.pipeline.enabled=false",
      "metertrail.secure-cookies=false"
    })
@AutoConfigureMockMvc
@Testcontainers
class SupportMessagesIntegrationTest {
  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", pg::getJdbcUrl);
    r.add("spring.datasource.username", pg::getUsername);
    r.add("spring.datasource.password", pg::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired Store store;
  @MockitoBean RateLimits limits;
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

  String body(Object value) throws Exception {
    return json.writeValueAsString(value);
  }

  Map<String, Object> root(String title, String text) {
    return Map.of("clientId", UUID.randomUUID(), "title", title, "body", text);
  }

  UUID create(Cookie cookie, Map<String, Object> b) throws Exception {
    String response =
        mvc.perform(
                post("/api/messages")
                    .cookie(cookie)
                    .header("X-CSRF-Token", csrf)
                    .contentType("application/json")
                    .content(body(b)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).path("id").asText());
  }

  void reply(Cookie cookie, String base, UUID id, Map<String, Object> b) throws Exception {
    mvc.perform(
            post(base + "/" + id + "/replies")
                .cookie(cookie)
                .header("X-CSRF-Token", csrf)
                .contentType("application/json")
                .content(body(b)))
        .andExpect(status().isOk());
  }

  @Test
  void anonymousAndCsrfAreBlocked() throws Exception {
    mvc.perform(get("/api/messages")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/messages")
                .cookie(userCookie)
                .contentType("application/json")
                .content(body(root("Title", "Text"))))
        .andExpect(status().isForbidden());
    UUID id = create(userCookie, root("Title", "Text"));
    mvc.perform(
            post("/api/admin/messages/" + id + "/replies")
                .cookie(adminCookie)
                .contentType("application/json")
                .content(body(Map.of("clientId", UUID.randomUUID(), "body", "reply"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void ownerIsolationAndAdminAuthorizationAreEnforced() throws Exception {
    UUID id = create(userCookie, root("Private", "private root"));
    mvc.perform(get("/api/messages").cookie(otherCookie)).andExpect(jsonPath("$.total").value(0));
    mvc.perform(get("/api/messages/" + id).cookie(otherCookie)).andExpect(status().isNotFound());
    mvc.perform(
            post("/api/messages/" + id + "/replies")
                .cookie(otherCookie)
                .header("X-CSRF-Token", csrf)
                .contentType("application/json")
                .content(body(Map.of("clientId", UUID.randomUUID(), "body", "spoof"))))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/admin/messages").cookie(userCookie)).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/messages/" + id).cookie(userCookie))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/messages/" + id).cookie(adminCookie))
        .andExpect(jsonPath("$.thread.body").value("private root"));
  }

  @Test
  void repliesStayUnderTheOriginalThreadAndRolesAreServerDerived() throws Exception {
    UUID id = create(userCookie, root("Help", "Original"));
    reply(
        userCookie,
        "/api/messages",
        id,
        Map.of("clientId", UUID.randomUUID(), "body", "follow-up", "sender_kind", "admin"));
    reply(
        adminCookie,
        "/api/admin/messages",
        id,
        Map.of("clientId", UUID.randomUUID(), "body", "Answer"));
    mvc.perform(get("/api/messages/" + id).cookie(userCookie))
        .andExpect(jsonPath("$.thread.body").value("Original"))
        .andExpect(jsonPath("$.thread.reply_count").value(2))
        .andExpect(jsonPath("$.replies[0].sender_kind").value("user"))
        .andExpect(jsonPath("$.replies[1].sender_kind").value("admin"))
        .andExpect(jsonPath("$.replies[1].body").value("Answer"));
  }

  @Test
  void retryIsIdempotentAndChangedContentConflicts() throws Exception {
    var b = root("Help", "Original");
    UUID id = create(userCookie, b);
    assertEquals(id, create(userCookie, b));
    var r = Map.<String, Object>of("clientId", UUID.randomUUID(), "body", "Answer");
    reply(adminCookie, "/api/admin/messages", id, r);
    reply(adminCookie, "/api/admin/messages", id, r);
    assertEquals(
        1,
        store.db.queryForObject(
            "SELECT reply_count FROM support_threads WHERE id=?", Integer.class, id));
    var changed = new HashMap<>(b);
    changed.put("body", "changed");
    mvc.perform(
            post("/api/messages")
                .cookie(userCookie)
                .header("X-CSRF-Token", csrf)
                .contentType("application/json")
                .content(body(changed)))
        .andExpect(status().isConflict());
    var changedReply = new HashMap<>(r);
    changedReply.put("body", "changed");
    mvc.perform(
            post("/api/admin/messages/" + id + "/replies")
                .cookie(adminCookie)
                .header("X-CSRF-Token", csrf)
                .contentType("application/json")
                .content(body(changedReply)))
        .andExpect(status().isConflict());
  }

  @Test
  void revokedRoleIsEffectiveForExistingSessions() throws Exception {
    mvc.perform(get("/api/me").cookie(adminCookie)).andExpect(jsonPath("$.isAdmin").value(true));
    store.db.update("UPDATE users SET is_admin=false WHERE id=?", admin);
    mvc.perform(get("/api/admin/messages").cookie(adminCookie)).andExpect(status().isForbidden());
    mvc.perform(get("/api/me").cookie(adminCookie)).andExpect(jsonPath("$.isAdmin").value(false));
    store.db.update("UPDATE users SET deleted_at=now() WHERE id=?", admin);
    mvc.perform(get("/api/messages").cookie(adminCookie)).andExpect(status().isUnauthorized());
  }

  @Test
  void validationRejectsBlankOversizedUtf8AndBadPagination() throws Exception {
    for (var b :
        List.of(
            root(" ", "text"),
            root("Title", " "),
            root("界".repeat(81), "text"),
            root("Title", "界".repeat(2667))))
      mvc.perform(
              post("/api/messages")
                  .cookie(userCookie)
                  .header("X-CSRF-Token", csrf)
                  .contentType("application/json")
                  .content(body(b)))
          .andExpect(status().isBadRequest());
    mvc.perform(get("/api/messages?size=99999").cookie(userCookie))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/messages?page=-1").cookie(userCookie)).andExpect(status().isBadRequest());
    mvc.perform(get("/api/messages/not-a-uuid").cookie(userCookie))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listIsPaginatedAndNeverContainsOtherOwnersBodies() throws Exception {
    for (int i = 0; i < 7; i++) create(userCookie, root("Subject " + i, "private"));
    create(otherCookie, root("Other", "other private"));
    mvc.perform(get("/api/messages?size=5&page=1").cookie(userCookie))
        .andExpect(jsonPath("$.total").value(7))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].body").doesNotExist());
  }

  @Test
  void earlierReplyPaginationPreservesOrderAndRoot() throws Exception {
    UUID id = create(userCookie, root("Help", "Original"));
    for (int i = 1; i <= 55; i++)
      reply(
          adminCookie,
          "/api/admin/messages",
          id,
          Map.of("clientId", UUID.randomUUID(), "body", "reply " + i));
    mvc.perform(get("/api/messages/" + id).cookie(userCookie))
        .andExpect(jsonPath("$.hasMore").value(true))
        .andExpect(jsonPath("$.replies.length()").value(50))
        .andExpect(jsonPath("$.replies[0].sequence").value(6))
        .andExpect(jsonPath("$.replies[49].sequence").value(55));
    mvc.perform(get("/api/messages/" + id + "?before=6").cookie(userCookie))
        .andExpect(jsonPath("$.thread.body").value("Original"))
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.replies.length()").value(5))
        .andExpect(jsonPath("$.replies[0].sequence").value(1));
  }

  @Test
  void replyLimitDoesNotPreventIdempotentRetry() throws Exception {
    UUID id = create(userCookie, root("Help", "Original"));
    var b = Map.<String, Object>of("clientId", UUID.randomUUID(), "body", "final answer");
    reply(adminCookie, "/api/admin/messages", id, b);
    store.db.update("UPDATE support_threads SET reply_count=200 WHERE id=?", id);
    reply(adminCookie, "/api/admin/messages", id, b);
    mvc.perform(
            post("/api/admin/messages/" + id + "/replies")
                .cookie(adminCookie)
                .header("X-CSRF-Token", csrf)
                .contentType("application/json")
                .content(body(Map.of("clientId", UUID.randomUUID(), "body", "extra"))))
        .andExpect(status().isConflict());
  }

  @Test
  void concurrentRepliesHaveUniqueMonotonicSequences() throws Exception {
    UUID id = create(userCookie, root("Help", "Original"));
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> tasks = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        tasks.add(
            pool.submit(
                () -> {
                  try {
                    reply(
                        adminCookie,
                        "/api/admin/messages",
                        id,
                        Map.of("clientId", UUID.randomUUID(), "body", "parallel"));
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                }));
      for (var f : tasks) f.get(20, TimeUnit.SECONDS);
      assertEquals(
          List.of(1, 2, 3, 4, 5, 6, 7, 8),
          store.db.queryForList(
              "SELECT sequence FROM support_replies WHERE thread_id=? ORDER BY sequence",
              Integer.class,
              id));
      assertEquals(
          8,
          store.db.queryForObject(
              "SELECT reply_count FROM support_threads WHERE id=?", Integer.class, id));
    } finally {
      pool.shutdownNow();
    }
  }
}
