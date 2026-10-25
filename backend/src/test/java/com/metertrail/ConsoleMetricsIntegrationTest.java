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
      "metertrail.ingest.pipeline.enabled=false",
      "metertrail.secure-cookies=false"
    })
@AutoConfigureMockMvc
@Testcontainers
class ConsoleMetricsIntegrationTest {
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

  @Test void requestMetricsDatesSortingAndIsolation() throws Exception {
    UUID project=UUID.randomUUID();
    UUID workspace=store.db.queryForObject("SELECT id FROM workspaces WHERE owner_id=?",UUID.class,owner);
    store.db.update("INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'report')",project,workspace);
    String trace="1234567890abcdef1234567890abcdef";
    var start=java.time.Instant.now().minusSeconds(3600);
    for(String type:List.of("trace","observation"))store.db.update("INSERT INTO objects(project_id,type,object_id,trace_id,revision,started_at,data,cost) VALUES(?,?,?,?,1,?::timestamptz,?::jsonb,?)",project,type,type,trace,start.toString(),type.equals("trace")?"{\"name\":\"request\",\"durationMs\":100}":"{\"observationType\":\"generation\",\"model\":\"demo\",\"durationMs\":9000,\"usage\":{\"inputTokens\":100,\"outputTokens\":20,\"cachedInputTokens\":0}}",type.equals("trace")?null:new java.math.BigDecimal("0.25"));
    var overview=json.readTree(mvc.perform(get("/api/projects/"+project+"/overview").cookie(userCookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertEquals(100,overview.path("summary").path("p95").asInt());
    assertEquals(1,overview.path("daily").get(0).path("calls").asInt());
    String url="/api/reports/traces";
    var result=json.readTree(mvc.perform(get(url).cookie(userCookie).param("project",project.toString()).param("sort","estimated_cost")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    var row=result.path("items").get(0);assertEquals(120,row.path("tokens").asInt());assertEquals(0.25,row.path("estimated_cost").asDouble());assertEquals("demo",row.path("models").asText());assertEquals(1,row.path("priced_calls").asInt());
    mvc.perform(get(url).cookie(userCookie).param("project",project.toString()).param("sort","cost;DROP TABLE users")).andExpect(status().isBadRequest());
    mvc.perform(get(url).cookie(otherCookie).param("project",project.toString())).andExpect(status().isNotFound());
    var empty=json.readTree(mvc.perform(get(url).cookie(userCookie).param("project",project.toString()).param("from",start.plusSeconds(1).toString()).param("to",start.plusSeconds(2).toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertEquals(0,empty.path("total").asInt());
    mvc.perform(get(url).cookie(userCookie).param("project",project.toString()).param("from","bad").param("to","bad")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/projects/"+project+"/traces/"+trace).cookie(userCookie)).andExpect(status().isOk());
  }
}
