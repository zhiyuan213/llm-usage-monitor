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
    for(String reason:List.of("usage_missing","model_missing","pricing_evidence_missing")) {
      String t=UUID.randomUUID().toString().replace("-","");
      String details=reason.equals("usage_missing")?"{\"observationType\":\"generation\",\"provider\":\"demo\",\"model\":\"demo\"}":reason.equals("model_missing")?"{\"observationType\":\"generation\",\"usage\":{\"inputTokens\":0,\"outputTokens\":0,\"cachedInputTokens\":0}}":"{\"observationType\":\"generation\",\"provider\":\"demo\",\"model\":\"demo\",\"usage\":{\"inputTokens\":0,\"outputTokens\":0,\"cachedInputTokens\":0}}";
      store.db.update("INSERT INTO objects(project_id,type,object_id,trace_id,revision,started_at,data) VALUES(?,'trace',?,?,1,?::timestamptz,'{}'::jsonb),(?,'observation',?,?,1,?::timestamptz,?::jsonb)",project,"trace-"+reason,t,start.toString(),project,reason,t,start.toString(),details);
      mvc.perform(get(url).cookie(userCookie).param("project",project.toString()).param("cost","unknown").param("costReason",reason).param("from",start.minusSeconds(1).toString()).param("to",start.plusSeconds(1).toString())).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    }
    mvc.perform(get(url).cookie(userCookie).param("project",project.toString()).param("cost","known")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    mvc.perform(get(url).cookie(userCookie).param("project",project.toString()).param("costReason","unsafe")).andExpect(status().isBadRequest());
    store.db.update("UPDATE workspaces SET plan='Core' WHERE id=?",workspace);
    mvc.perform(get("/api/projects/"+project+"/insights/costs").cookie(userCookie).param("from",start.plusSeconds(1).toString()).param("to",start.plusSeconds(2).toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data_state.kind").value("window_empty"));
    mvc.perform(get("/api/projects/"+project+"/insights/costs").cookie(userCookie).param("from",start.minus(java.time.Duration.ofDays(121)).toString()).param("to",start.minus(java.time.Duration.ofDays(120)).toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data_state.kind").value("outside_retention"));
    store.db.update("UPDATE workspaces SET subscription_status='canceled' WHERE id=?",workspace);
    mvc.perform(get("/api/ingestion-status")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/ingestion-status").cookie(userCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("subscription_inactive"));
    mvc.perform(get("/api/ingestion-status").cookie(otherCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("ready"));
    mvc.perform(get("/api/workspace").cookie(userCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.projects[0].last_activity_at").exists());
    mvc.perform(get(url).cookie(userCookie).param("project",project.toString()).param("sort","cost;DROP TABLE users")).andExpect(status().isBadRequest());
    mvc.perform(get(url).cookie(otherCookie).param("project",project.toString())).andExpect(status().isNotFound());
    var empty=json.readTree(mvc.perform(get(url).cookie(userCookie).param("project",project.toString()).param("from",start.plusSeconds(1).toString()).param("to",start.plusSeconds(2).toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertEquals(0,empty.path("total").asInt());
    mvc.perform(get(url).cookie(userCookie).param("project",project.toString()).param("from","bad").param("to","bad")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/projects/"+project+"/traces/"+trace).cookie(userCookie)).andExpect(status().isOk());
  }

  @Test void twoModelCallsInOneTraceAreOneOverviewRequestAndOneListRow() throws Exception {
    UUID project=UUID.randomUUID();
    UUID workspace=store.db.queryForObject("SELECT id FROM workspaces WHERE owner_id=?",UUID.class,owner);
    store.db.update("INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'multi-model request')",project,workspace);
    String trace=UUID.randomUUID().toString().replace("-","");
    var start=java.time.Instant.now().minusSeconds(30);
    // Trace metadata may repeat aggregate usage. It must not count as a model call.
    store.db.update("INSERT INTO objects(project_id,type,object_id,trace_id,revision,started_at,data) VALUES(?,'trace','group-trace',?,1,?::timestamptz,?::jsonb)",project,trace,start.toString(),json.writeValueAsString(Map.of("name","One business request","status","success","durationMs",3000,"observationType","generation","usage",Map.of("inputTokens",28,"outputTokens",158,"cachedInputTokens",0))));
    for(int i=0;i<2;i++)store.db.update("INSERT INTO objects(project_id,type,object_id,trace_id,revision,started_at,data,cost) VALUES(?,'observation',?,?,1,?::timestamptz,?::jsonb,0.0001)",project,"model-step-"+i,trace,start.plusSeconds(i).toString(),json.writeValueAsString(Map.of("name","Model step","model","model-"+i,"observationType","generation","status","success","durationMs",1000,"usage",Map.of("inputTokens",14,"outputTokens",79,"cachedInputTokens",0))));
    mvc.perform(get("/api/projects/"+project+"/overview").cookie(userCookie))
        .andExpect(status().isOk()).andExpect(jsonPath("$.summary.traces").value(1))
        .andExpect(jsonPath("$.summary.calls").value(2)).andExpect(jsonPath("$.summary.objects").value(3))
        .andExpect(jsonPath("$.summary.tokens").value(186)).andExpect(jsonPath("$.summary.cost").value(0.0002))
        .andExpect(jsonPath("$.summary.unknown_costs").value(0)).andExpect(jsonPath("$.summary.slow_requests").value(0));
    mvc.perform(get("/api/reports/traces").cookie(userCookie).param("project",project.toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].tokens").value(186)).andExpect(jsonPath("$.items[0].priced_calls").value(2));
    mvc.perform(get("/api/projects/"+project+"/traces/"+trace).cookie(userCookie))
        .andExpect(status().isOk()).andExpect(jsonPath("$.objects.length()").value(3));
  }

  @Test void observationFiltersIgnoreTotalTraceDurationAndRootStatus() throws Exception {
    UUID project=UUID.randomUUID();
    UUID workspace=store.db.queryForObject("SELECT id FROM workspaces WHERE owner_id=?",UUID.class,owner);
    store.db.update("INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'observation filters')",project,workspace);
    String[] ids={"long-root","slow-child-root","failed-child-root"};
    for(int i=0;i<ids.length;i++) {
      String trace=String.format("%032x",i+1);
      store.db.update("INSERT INTO objects(project_id,type,object_id,revision,trace_id,started_at,data) VALUES(?,'trace',?,1,?,now(),?::jsonb)",project,ids[i],trace,json.writeValueAsString(Map.of("name",ids[i],"status",i==0?"error":"success","durationMs",i==0?30000:1000)));
      for(int j=0;j<5;j++)store.db.update("INSERT INTO objects(project_id,type,object_id,revision,trace_id,started_at,data) VALUES(?,'observation',?,1,?,now(),?::jsonb)",project,ids[i]+"-step-"+j,trace,json.writeValueAsString(Map.of("status",i==2&&j==0?"error":"success","durationMs",i==1&&j==0?10000:9999)));
    }
    String endpoint="/api/reports/traces";
    for(String filter:List.of("slow","error"))mvc.perform(get(endpoint).cookie(userCookie).param("project",project.toString()).param("observation",filter)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].object_id").value(filter.equals("slow")?"slow-child-root":"failed-child-root"));
    String threshold="/api/projects/"+project+"/slow-threshold";
    assertEquals(10000,store.db.queryForObject("SELECT slow_call_threshold_ms FROM projects WHERE id=?",Integer.class,project));
    mvc.perform(post(threshold).cookie(userCookie).contentType("application/json").content("{\"seconds\":15}")).andExpect(status().isForbidden());
    mvc.perform(post(threshold).cookie(otherCookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"seconds\":15}")).andExpect(status().is4xxClientError());
    for(String seconds:List.of("0","601","1.5"))mvc.perform(post(threshold).cookie(userCookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"seconds\":"+seconds+"}")).andExpect(status().isBadRequest());
    mvc.perform(post(threshold).cookie(userCookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"seconds\":15}")).andExpect(status().isOk()).andExpect(jsonPath("$.slow_call_threshold_ms").value(15000));
    mvc.perform(get(endpoint).cookie(userCookie).param("project",project.toString()).param("observation","slow")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    mvc.perform(get("/api/projects/"+project+"/overview").cookie(userCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.summary.slow_requests").value(0));
    mvc.perform(get(endpoint).cookie(userCookie).param("project",project.toString()).param("observation","invalid")).andExpect(status().isBadRequest());
    mvc.perform(get(endpoint).cookie(otherCookie).param("project",project.toString()).param("observation","slow")).andExpect(status().isNotFound());
  }

  @Test void overviewCountsTraceRequestsAndDeleteRequiresLiteralAndProof() throws Exception {
    UUID project=UUID.randomUUID();
    UUID workspace=store.db.queryForObject("SELECT id FROM workspaces WHERE owner_id=?",UUID.class,owner);
    store.db.update("INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'report')",project,workspace);
    var start=java.time.Instant.now().minusSeconds(3600);
    String[] types={"trace","trace","trace","observation","score","observation","observation"};
    String[] statuses={"success","error","cancelled","error","error","success","success"};
    int[] durations={9999,10000,11000,12000,12000,10000,9999};
    for(int i=0;i<types.length;i++)store.db.update("INSERT INTO objects(project_id,type,object_id,revision,started_at,data) VALUES(?,?,?,1,?::timestamptz,?::jsonb)",project,types[i],"object-"+i,start.toString(),json.writeValueAsString(Map.of("status",statuses[i],"durationMs",durations[i])));
    String endpoint="/api/projects/"+project;
    mvc.perform(get(endpoint+"/overview").cookie(userCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.summary.traces").value(3)).andExpect(jsonPath("$.summary.slow_requests").value(2)).andExpect(jsonPath("$.summary.failed_requests").value(1));
    mvc.perform(get(endpoint+"/overview").cookie(userCookie).param("from",start.plusSeconds(1).toString()).param("to",start.plusSeconds(2).toString())).andExpect(status().isOk()).andExpect(jsonPath("$.summary.traces").value(0)).andExpect(jsonPath("$.summary.slow_requests").value(0)).andExpect(jsonPath("$.summary.failed_requests").value(0));
    mvc.perform(get(endpoint+"/overview").cookie(otherCookie)).andExpect(status().isNotFound());
    for(String confirm:List.of("report","DELETE"," delete",""))mvc.perform(post(endpoint+"/delete").cookie(userCookie).header("X-CSRF-Token",csrf).contentType("application/json").content(json.writeValueAsString(Map.of("confirm",confirm)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"));
    UUID challenge=UUID.randomUUID();String code="123456";
    store.db.update("INSERT INTO verification_challenges(id,user_id,action,target,code_hash,expires_at) VALUES(?,?,'project.delete',?,?,now()+interval '5 minutes')",challenge,owner,project.toString(),Support.hmac(store.secrets.secret,challenge+":"+code));
    mvc.perform(post(endpoint+"/delete").cookie(userCookie).header("X-CSRF-Token",csrf).contentType("application/json").content(json.writeValueAsString(Map.of("confirm","delete","challengeId",challenge.toString(),"code","000000")))).andExpect(status().isBadRequest());
    assertNull(store.db.queryForMap("SELECT deleted_at FROM projects WHERE id=?",project).get("deleted_at"));
    mvc.perform(post(endpoint+"/delete").cookie(userCookie).header("X-CSRF-Token",csrf).contentType("application/json").content(json.writeValueAsString(Map.of("confirm","delete","challengeId",challenge.toString(),"code",code)))).andExpect(status().isOk());
    assertNotNull(store.db.queryForMap("SELECT deleted_at FROM projects WHERE id=?",project).get("deleted_at"));
  }
}
