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
class AdminProjectsIntegrationTest {
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

  @Test void permissionsAndCalendarAggregation() throws Exception {
    mvc.perform(get("/api/admin/projects").cookie(userCookie)).andExpect(status().isForbidden());
    UUID project=UUID.randomUUID(), empty=UUID.randomUUID();
    UUID workspace=store.db.queryForObject("SELECT id FROM workspaces WHERE owner_id=?",UUID.class,owner);
    store.db.update("INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'metrics'),(?,?,'empty')",project,workspace,empty,workspace);
    store.db.update("UPDATE workspaces SET plan='Pro' WHERE id=?",workspace);
    int year=java.time.Year.now(java.time.ZoneOffset.UTC).getValue();
    String at=year+"-06-15T00:00:00Z";
    for(String type:List.of("trace","observation"))store.db.update(
      "INSERT INTO objects(project_id,type,object_id,revision,started_at,data,cost) VALUES(?,?,?,1,?::timestamptz,?::jsonb,?)",
      project,type,type,at,type.equals("trace")?"{}":"{\"observationType\":\"generation\",\"usage\":{\"inputTokens\":100,\"outputTokens\":20}}",type.equals("trace")?null:new java.math.BigDecimal("0.25"));
    var response=mvc.perform(get("/api/admin/projects").cookie(adminCookie).param("account",owner.toString()).param("year",String.valueOf(year)).param("month","6").param("sort","name"))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var result=json.readTree(response);
    assertEquals(2,result.path("total").asInt());
    var row=result.path("items").get(1);
    assertEquals("metrics",row.path("name").asText());
    assertEquals(1,row.path("calls").asInt());assertEquals(2,row.path("objects").asInt());
    assertEquals(120,row.path("tokens").asInt());assertEquals(0.25,row.path("cost").asDouble());
    assertEquals(120,result.path("chart").get(0).path("tokens").asInt());
    for(String key:List.of("email","name","environment","calls","objects","tokens","cost","unknown_costs")) {
      mvc.perform(get("/api/admin/projects").cookie(adminCookie).param("account",owner.toString()).param("year",String.valueOf(year)).param("sort",key).param("direction","desc"))
        .andExpect(status().isOk());
    }
    mvc.perform(get("/api/admin/projects").cookie(adminCookie).param("sort","email; DROP TABLE users")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/admin/projects").cookie(adminCookie).param("account",owner.toString()).param("sort","tokens").param("direction","desc"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("metrics"));
    mvc.perform(get("/api/admin/projects").cookie(adminCookie).param("account",owner.toString()).param("year",String.valueOf(year)).param("month","7").param("sort","name"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.items[1].objects").value(0)).andExpect(jsonPath("$.items[1].tokens").doesNotExist());
    mvc.perform(get("/api/admin/projects").cookie(adminCookie).param("month","13")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/admin/projects").cookie(adminCookie).param("account",other.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
  }
}
