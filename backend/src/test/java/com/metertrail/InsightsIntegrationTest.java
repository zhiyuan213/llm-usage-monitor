package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
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

@SpringBootTest(properties={"metertrail.secret=test-secret-at-least-32-characters-long","metertrail.worker-enabled=false","metertrail.ingest.pipeline.enabled=false","metertrail.secure-cookies=false"})
@AutoConfigureMockMvc
@Testcontainers
class InsightsIntegrationTest {
  @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);r.add("spring.datasource.password",pg::getPassword);
  }
  @Autowired Store store;@Autowired Insights insights;@Autowired WeeklyInsights weekly;@Autowired MockMvc mvc;
  @Autowired ReliabilityInsights reliability;@Autowired BudgetAlerts budgets;
  @MockitoBean RateLimits limits;
  final ObjectMapper json=new ObjectMapper();
  UUID user,workspace,project,other;Cookie cookie,otherCookie;String csrf="insights-test-csrf";
  Instant end,middle,start;
  Cookie account(UUID id,String plan) {
    store.db.update("INSERT INTO users(id,email,verified,language) VALUES(?,?,true,'zh-CN')",id,id+"@test.local");
    UUID w=UUID.randomUUID();store.db.update("INSERT INTO workspaces(id,owner_id,name,plan) VALUES(?,?,'insights test',?)",w,id,plan);
    String token=Support.token();store.db.update("INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1 hour')",Support.hash(token),id,csrf);
    return new Cookie("mt_session",token);
  }
  @BeforeEach void setup() {
    user=UUID.randomUUID();other=UUID.randomUUID();cookie=account(user,"Core");otherCookie=account(other,"Core");
    workspace=(UUID)store.workspace(user).get("id");project=UUID.randomUUID();
    store.db.update("INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'insights project')",project,workspace);
    end=Instant.now().minusSeconds(30);middle=end.minus(Duration.ofDays(7));start=middle.minus(Duration.ofDays(7));
  }
  void object(String type,Instant at,String data,String cost) {
    store.db.update("INSERT INTO objects(project_id,type,object_id,revision,trace_id,started_at,data,cost) VALUES(?,?,?,1,?,?,?::jsonb,?)",project,type,UUID.randomUUID().toString(),"trace-example",Timestamp.from(at),data,cost==null?null:new BigDecimal(cost));
  }
  void populate(Instant a,Instant b) {
    for(int i=0;i<20;i++) {
      object("trace",a,"{\"name\":\"before\",\"status\":\"success\",\"durationMs\":100}","999");
      object("observation",a,"{\"name\":\"model call\",\"observationType\":\"generation\",\"feature\":\"support\",\"provider\":\"test\",\"model\":\"small\",\"usage\":{\"inputTokens\":100,\"outputTokens\":10,\"cachedInputTokens\":0}}","2");
      object("trace",b,"{\"name\":\"after\",\"status\":\""+(i<2?"error":"success")+"\",\"durationMs\":200}","999");
      object("observation",b,"{\"name\":\"model call\",\"observationType\":\"generation\",\"feature\":\"support\",\"provider\":\"test\",\"model\":\"large\",\"usage\":{\"inputTokens\":200,\"outputTokens\":10,\"cachedInputTokens\":0}}","4");
    }
  }
  String url(String tail){return "/api/projects/"+project+"/insights/"+tail;}
  void budget(String amount,int warning,boolean enabled,boolean forecast) throws Exception {
    mvc.perform(post(url("budget")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json")
      .content("{\"monthlyBudget\":\""+amount+"\",\"warningPercent\":"+warning+",\"enabled\":"+enabled+",\"forecastEnabled\":"+forecast+"}"))
      .andExpect(status().isOk());
  }
  void budgetCalls(Instant at,int count,String cost) {
    for(int i=0;i<count;i++)object("observation",at,"{\"observationType\":\"generation\",\"status\":\"success\",\"durationMs\":100,\"provider\":\"test\",\"model\":\"small\",\"usage\":{\"inputTokens\":100,\"outputTokens\":10}}",cost);
  }
  @Test void weeklyTestEmailIsSeparateThrottledAndCancelled() throws Exception {
    mvc.perform(get(url("weekly/example")).cookie(otherCookie)).andExpect(status().isNotFound());
    assertTrue(getJson(url("weekly/example")).path("example").asBoolean());
    mvc.perform(post(url("weekly/test")).cookie(cookie)).andExpect(status().isForbidden());
    mvc.perform(post(url("weekly/test")).cookie(otherCookie).header("X-CSRF-Token",csrf)).andExpect(status().isNotFound());
    mvc.perform(post(url("weekly/test")).cookie(cookie).header("X-CSRF-Token",csrf)).andExpect(status().isConflict());
    mvc.perform(post(url("weekly")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"enabled\":true,\"timezone\":\"UTC\"}")).andExpect(status().isOk());
    Object due=store.db.queryForObject("SELECT next_due FROM insight_weekly_subscriptions WHERE project_id=?",Object.class,project);
    for(String expected:List.of("queued","cooldown")) {
      var response=mvc.perform(post(url("weekly/test")).cookie(cookie).header("X-CSRF-Token",csrf)).andExpect(status().isOk()).andReturn();
      assertEquals(expected,json.readTree(response.getResponse().getContentAsString()).path("status").asText());
    }
    var row=store.db.queryForMap("SELECT o.* FROM outbox o JOIN insight_weekly_reports r ON r.outbox_id=o.id WHERE r.project_id=? AND r.test_email",project);
    assertTrue(row.get("subject").toString().startsWith("[TEST]"));
    assertTrue(WeeklyInsights.mailEligible(store,row.get("id")));
    assertEquals(due,store.db.queryForObject("SELECT next_due FROM insight_weekly_subscriptions WHERE project_id=?",Object.class,project));
    assertTrue(weekly.sendNow(project,Instant.now()));
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM insight_weekly_reports WHERE project_id=? AND NOT test_email",Integer.class,project));
    assertTrue(getJson(url("weekly")).path("items").findValues("test_email").stream().anyMatch(JsonNode::asBoolean));
    mvc.perform(post(url("weekly")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"enabled\":false,\"timezone\":\"UTC\"}")).andExpect(status().isOk());
    assertFalse(WeeklyInsights.mailEligible(store,row.get("id")));
    assertEquals(10,store.db.queryForObject("SELECT attempts FROM outbox WHERE id=?",Integer.class,row.get("id")));
    store.db.update("UPDATE workspaces SET plan='Free' WHERE id=?",workspace);
    mvc.perform(post(url("weekly/test")).cookie(cookie).header("X-CSRF-Token",csrf)).andExpect(status().isForbidden());
  }
  @Test void reliabilityUsesSeparatePopulationsAndCorrectCoverageAndPaging() throws Exception {
    Instant at=Instant.now().minusSeconds(3600);
    for(int i=0;i<20;i++)object("trace",at,"{\"name\":\"request\",\"status\":\""+(i<5?"error":i==5?"cancelled":"success")+"\",\"feature\":\"support\",\"error\":\"upstream timeout <script>\""+(i<2?"":",\"durationMs\":"+(i<10?1000:5000))+"}","999");
    budgetCalls(at,1,"0.1");
    var data=getJson(url("reliability"));
    assertEquals(5000,data.path("slow_ms").asInt());
    assertEquals(20,data.path("summary").path("total").asInt());assertEquals(5,data.path("summary").path("errors").asInt());
    assertEquals(1,data.path("summary").path("cancelled").asInt());assertEquals(18,data.path("summary").path("timed").asInt());
    assertEquals(10,data.path("summary").path("slow").asInt());assertEquals(0.25,data.path("summary").path("error_rate").asDouble());
    assertEquals(0.9,data.path("summary").path("timing_coverage").asDouble());assertEquals(5000,data.path("summary").path("p95_ms").asDouble());
    assertEquals(5,data.path("total").asInt());assertEquals(5,data.path("items").size());assertEquals(5,data.path("error_groups").get(0).path("count").asInt());
    assertEquals("upstream timeout <script>",data.path("error_groups").get(0).path("message").asText());
    data=getJson(url("reliability?view=slow&page=1&size=5"));assertEquals(10,data.path("total").asInt());assertEquals(5,data.path("items").size());
    assertEquals(1,getJson(url("reliability?scope=generation&dimension=model")).path("summary").path("total").asInt());
    mvc.perform(get(url("reliability?slowMs=3000")).cookie(cookie)).andExpect(status().isForbidden());
    store.db.update("UPDATE workspaces SET plan='Pro' WHERE id=?",workspace);
    assertEquals(10,getJson(url("reliability?slowMs=3000")).path("summary").path("slow").asInt());
    mvc.perform(get(url("reliability?scope=score")).cookie(cookie)).andExpect(status().isBadRequest());
    mvc.perform(get(url("reliability")).param("dimension","model' OR 1=1").cookie(cookie)).andExpect(status().isBadRequest());
    mvc.perform(get(url("reliability?page=-1")).cookie(cookie)).andExpect(status().isBadRequest());
    mvc.perform(get(url("reliability"))).andExpect(status().isUnauthorized());
    mvc.perform(get(url("reliability?slowMs=0")).cookie(cookie)).andExpect(status().isBadRequest());
    mvc.perform(get(url("reliability")).cookie(otherCookie)).andExpect(status().isNotFound());
  }
  @Test void performanceRanksByFailureRateAndLatencyWithFiveSecondDefault() throws Exception {
    Instant at=Instant.now().minusSeconds(60);
    for(int i=0;i<4;i++)object("trace",at,"{\"name\":\"request\",\"feature\":\"frequent\",\"status\":\""+(i<2?"error":"success")+"\",\"durationMs\":4999}","0");
    object("trace",at,"{\"name\":\"request\",\"feature\":\"highest-rate\",\"status\":\"error\",\"durationMs\":5000}","0");
    var data=getJson(url("reliability"));
    assertEquals(1,data.path("summary").path("slow").asInt());
    assertEquals("highest-rate",data.path("groups").get(0).path("label").asText());
    data=getJson(url("reliability?view=slow"));
    assertEquals("highest-rate",data.path("groups").get(0).path("label").asText());
    assertEquals(5000,data.path("groups").get(0).path("p95_ms").asDouble());
  }
  @Test void budgetValidatesPlanPrecisionAndOptIn() throws Exception {
    assertFalse(getJson(url("budget")).path("configured").asBoolean());
    mvc.perform(post(url("budget")).cookie(cookie).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    String body="{\"monthlyBudget\":100,\"warningPercent\":70,\"enabled\":true,\"forecastEnabled\":true}";
    mvc.perform(post(url("budget")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content(body)).andExpect(status().isForbidden());
    for(String amount:List.of("-1","0","1000000001","0.0000001","NaN"))
      mvc.perform(post(url("budget")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content(body.replace("100,","\""+amount+"\",").replace("70,","80,"))).andExpect(status().isBadRequest());
    store.db.update("UPDATE workspaces SET plan='Pro' WHERE id=?",workspace);budget("100",70,true,true);
    assertEquals(70,getJson(url("budget")).path("settings").path("warning_percent").asInt());
    store.db.update("UPDATE workspaces SET plan='Core' WHERE id=?",workspace);
    assertEquals(80,getJson(url("budget")).path("settings").path("warning_percent").asInt());
    store.db.update("UPDATE workspaces SET plan='Free' WHERE id=?",workspace);budget("100",80,false,true);
    assertFalse(getJson(url("budget")).path("available").asBoolean());assertTrue(getJson(url("budget")).path("usage").isNull());
    mvc.perform(get(url("budget")).cookie(otherCookie)).andExpect(status().isNotFound());
    mvc.perform(get(url("reliability")).cookie(cookie)).andExpect(status().isForbidden());
  }
  @Test void budgetCountsOnlyModelCostsAndDeduplicatesWarningLevelsAndMonths() throws Exception {
    Instant now=Instant.parse("2026-10-25T12:00:00Z");budget("100",80,true,false);
    store.db.update("UPDATE project_budgets SET next_check=? WHERE project_id=?",Timestamp.from(now.minusSeconds(1)),project);
    budgetCalls(now.minusSeconds(3600),20,"4");object("trace",now.minusSeconds(3600),"{\"status\":\"success\"}","9999");
    assertTrue(budgets.check(project,now));assertFalse(budgets.check(project,now.plusSeconds(301)));
    var snap=Support.parse(store.db.queryForObject("SELECT snapshot::text FROM budget_notifications WHERE project_id=?",String.class,project));
    assertEquals(80,snap.path("known_cost").asDouble());assertEquals("2026-10-01T00:00:00Z",snap.path("period_start").asText());
    budgetCalls(now.minusSeconds(60),1,"25");assertTrue(budgets.check(project,now.plusSeconds(602)));
    assertEquals(Set.of("warning","exceeded"),new HashSet<>(store.db.queryForList("SELECT kind FROM budget_notifications WHERE project_id=?",String.class,project)));
    Instant november=Instant.parse("2026-11-02T12:00:00Z");budgetCalls(november.minusSeconds(3600),1,"90");assertTrue(budgets.check(project,november));
    assertEquals(3,store.db.queryForObject("SELECT count(*) FROM budget_notifications WHERE project_id=?",Integer.class,project));
  }
  @Test void budgetForecastRequiresCompletePricesAndEnoughElapsedTime() throws Exception {
    Instant now=Instant.parse("2026-10-20T12:00:00Z");budget("1000",80,true,true);
    store.db.update("UPDATE project_budgets SET next_check=? WHERE project_id=?",Timestamp.from(now.minusSeconds(1)),project);
    budgetCalls(now.minusSeconds(3600),20,"39.5");budgetCalls(now.minusSeconds(3600),1,null);
    assertFalse(budgets.check(project,now));
    store.db.update("UPDATE objects SET cost=0 WHERE project_id=? AND cost IS NULL",project);
    assertTrue(budgets.check(project,now.plusSeconds(301)));
    assertEquals("forecast",store.db.queryForObject("SELECT kind FROM budget_notifications WHERE project_id=?",String.class,project));
    var settings=store.db.queryForMap("SELECT * FROM project_budgets WHERE project_id=?",project);
    store.db.update("UPDATE objects SET started_at=? WHERE project_id=?",Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")),project);
    var early=budgets.calculate(project,settings,"Core",Instant.parse("2026-10-02T12:00:00Z"));
    assertEquals(21L,Insights.count(early,"generations"));assertNull(early.get("projected_cost"));
  }
  @Test void budgetMailIsSuppressedAfterChangesAndOptOutCancelsPending() throws Exception {
    Instant now=Instant.now();budget("1",80,true,false);budgetCalls(now.minusSeconds(1),1,"2");
    assertTrue(budgets.check(project,now.plusSeconds(1)));
    Long mail=store.db.queryForObject("SELECT outbox_id FROM budget_notifications WHERE project_id=?",Long.class,project);
    assertTrue(BudgetAlerts.mailEligible(store,mail));
    store.db.update("UPDATE users SET email=? WHERE id=?","changed-"+user+"@test.local",user);assertFalse(BudgetAlerts.mailEligible(store,mail));
    store.db.update("UPDATE users SET email=? WHERE id=?",user+"@test.local",user);
    store.db.update("UPDATE projects SET deleted_at=now() WHERE id=?",project);assertFalse(BudgetAlerts.mailEligible(store,mail));
    store.db.update("UPDATE projects SET deleted_at=NULL WHERE id=?",project);
    store.db.update("UPDATE workspaces SET plan='Free' WHERE id=?",workspace);assertFalse(BudgetAlerts.mailEligible(store,mail));
    budget("1",80,false,false);assertEquals("",store.db.queryForObject("SELECT body FROM outbox WHERE id=?",String.class,mail));
    assertEquals("cancelled",getJson(url("budget")).path("items").get(0).path("status").asText());
  }
  @Test void budgetGenerationRollsBackAndConcurrentRetryQueuesOneEmail() throws Exception {
    Instant now=Instant.now();budget("1",80,true,false);budgetCalls(now.minusSeconds(1),1,"2");
    store.db.execute("CREATE FUNCTION reject_budget_notice() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test rollback'; END $$");
    store.db.execute("CREATE TRIGGER reject_budget_notice BEFORE INSERT ON budget_notifications FOR EACH ROW EXECUTE FUNCTION reject_budget_notice()");
    try {assertThrows(RuntimeException.class,()->budgets.check(project,now.plusSeconds(1)));assertEquals(0,store.db.queryForObject("SELECT count(*) FROM outbox WHERE budget_project_id=?",Integer.class,project));}
    finally {store.db.execute("DROP TRIGGER reject_budget_notice ON budget_notifications");store.db.execute("DROP FUNCTION reject_budget_notice()");}
    var pool=Executors.newFixedThreadPool(2);
    try {var a=pool.submit(()->budgets.check(project,now.plusSeconds(1)));var b=pool.submit(()->budgets.check(project,now.plusSeconds(1)));a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM budget_notifications WHERE project_id=?",Integer.class,project));
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM outbox WHERE budget_project_id=?",Integer.class,project));
  }
  JsonNode getJson(String url) throws Exception {return json.readTree(mvc.perform(get(url).cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
  @SuppressWarnings("unchecked")
  @Test void exactCostsCoverageAndRulesUseCorrectDenominators() {
    populate(start.plusSeconds(1),middle.plusSeconds(1));
    object("observation",end,"{\"observationType\":\"generation\"}","7000");
    var result=insights.analyze(user,project,start,middle,end,"feature");
    var before=(Map<String,Object>)result.get("before");var after=(Map<String,Object>)result.get("after");
    assertEquals(20L,Insights.count(after,"requests"));assertEquals(20L,Insights.count(after,"generations"));
    assertEquals(0,new BigDecimal("80").compareTo(Insights.number(after.get("known_cost"))));
    assertEquals(0,new BigDecimal("40").compareTo(Insights.number(before.get("known_cost"))));
    var c=(Map<String,Object>)result.get("comparison");
    assertEquals(0,new BigDecimal("100").compareTo(Insights.number(c.get("average_cost_change_pct"))));
    assertTrue(((List<?>)c.get("findings")).containsAll(List.of("COST_GROWTH","UNIT_COST_GROWTH","INPUT_GROWTH","ERRORS","LATENCY_GROWTH")));
    object("observation",middle.plusSeconds(2),"{\"observationType\":\"generation\",\"feature\":\"support\"}",null);
    result=insights.analyze(user,project,start,middle,end,"feature");after=(Map<String,Object>)result.get("after");c=(Map<String,Object>)result.get("comparison");
    assertNull(after.get("average_model_cost"));assertNull(c.get("cost_change_pct"));assertTrue(((List<?>)c.get("findings")).contains("MISSING_PRICES"));
  }
  @SuppressWarnings("unchecked")
  @Test void retentionMissingUsageAndZeroBaselineNeverInventComparisons() {
    object("observation",middle.plusSeconds(1),"{\"observationType\":\"generation\"}","0");
    var result=insights.analyze(user,project,start,middle,end,"model");var after=(Map<String,Object>)result.get("after");
    assertNull(after.get("average_input_tokens"));assertNull(((Map<?,?>)result.get("comparison")).get("cost_change_pct"));
    Instant old=Instant.now().minus(Duration.ofDays(85));
    result=insights.analyze(user,project,old.minus(Duration.ofDays(7)),old,old.plus(Duration.ofDays(7)),"feature");
    assertEquals(false,((Map<?,?>)result.get("comparison")).get("retention_complete"));
    assertThrows(ApiError.class,()->insights.analyze(user,project,start,middle,end,"feature' OR 1=1"));
  }
  @Test void authenticationPlanCsrfAndOwnershipAreEnforced() throws Exception {
    mvc.perform(get(url("costs"))).andExpect(status().isUnauthorized());
    mvc.perform(get(url("costs")).cookie(otherCookie)).andExpect(status().isNotFound());
    mvc.perform(post(url("releases")).cookie(cookie).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
    store.db.update("UPDATE workspaces SET plan='Free' WHERE id=?",workspace);
    mvc.perform(get(url("costs")).cookie(cookie)).andExpect(status().isForbidden());
    mvc.perform(post(url("weekly")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"enabled\":true,\"timezone\":\"UTC\"}")).andExpect(status().isForbidden());
    mvc.perform(post(url("weekly")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"enabled\":false,\"timezone\":\"UTC\"}")).andExpect(status().isOk());
  }
  @SuppressWarnings("unchecked")
  @Test void analysisUsesCommittedReadPoolSnapshotInsideWriteTransaction() {
    var tx=new org.springframework.transaction.support.TransactionTemplate(
      new org.springframework.jdbc.datasource.DataSourceTransactionManager(store.db.getDataSource()));
    tx.execute(status->{
      object("observation",middle.plusSeconds(1),"{\"observationType\":\"generation\"}","9999");
      var result=insights.analyze(user,project,start,middle,end,"feature");
      assertEquals(0L,Insights.count((Map<String,Object>)result.get("after"),"generations"));
      status.setRollbackOnly();return null;
    });
  }
  @Test void releaseWindowsAreSymmetricPartialScopedAndAudited() throws Exception {
    Instant at=Instant.now().minus(Duration.ofHours(2));
    var body="{\"name\":\"release <script>\",\"releasedAt\":\""+at+"\"}";
    var response=mvc.perform(post(url("releases")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse();
    String id=json.readTree(response.getContentAsString()).path("id").asText();
    JsonNode result=getJson(url("releases/"+id));assertFalse(result.path("window_complete").asBoolean());
    Instant b=Instant.parse(result.path("before").path("start").asText());Instant m=Instant.parse(result.path("before").path("end").asText());Instant e=Instant.parse(result.path("after").path("end").asText());
    assertEquals(Duration.between(b,m),Duration.between(m,e));
    mvc.perform(get(url("releases/"+id)).cookie(otherCookie)).andExpect(status().isNotFound());
    mvc.perform(post(url("releases")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"name\":\"future\",\"releasedAt\":\""+Instant.now().plusSeconds(3600)+"\"}")).andExpect(status().isBadRequest());
    mvc.perform(post(url("releases/"+id+"/delete")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{}")).andExpect(status().isOk());
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM audit_logs WHERE user_id=? AND action='release.delete'",Integer.class,user));
  }
  void subscribe(Instant due) {
    store.db.update("INSERT INTO insight_weekly_subscriptions(project_id,enabled,timezone,next_due) VALUES(?,true,'UTC',?)",project,Timestamp.from(due));
  }
  @Test void manualSendIsAuthorizedDeduplicatedAndDoesNotMoveSchedule() throws Exception {
    String send=url("weekly/send");
    mvc.perform(post(send).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    mvc.perform(post(send).cookie(cookie).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    mvc.perform(post(send).cookie(otherCookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{}")).andExpect(status().isNotFound());
    mvc.perform(post(send).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{}")).andExpect(status().isConflict());
    Instant due=Instant.now().plus(Duration.ofDays(5));subscribe(due);
    for(String expected:List.of("queued","already_generated")) {
      mvc.perform(post(send).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(expected));
    }
    assertEquals(Timestamp.from(due).getTime(),store.db.queryForObject("SELECT next_due FROM insight_weekly_subscriptions WHERE project_id=?",Timestamp.class,project).getTime());
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM insight_weekly_reports WHERE project_id=?",Integer.class,project));
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM outbox WHERE insight_project_id=?",Integer.class,project));
    store.db.update("UPDATE workspaces SET plan='Free' WHERE id=?",workspace);
    mvc.perform(post(send).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{}")).andExpect(status().isForbidden());
  }
  @Test void weeklyGenerationIsConcurrentSafeAndHistoryIsPrivate() throws Exception {
    Instant now=Instant.now();subscribe(now.minusSeconds(1));
    var pool=Executors.newFixedThreadPool(2);
    try {var a=pool.submit(()->weekly.generate(project,now));var b=pool.submit(()->weekly.generate(project,now));a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
    assertEquals(1,store.db.queryForObject("SELECT count(*) FROM insight_weekly_reports WHERE project_id=?",Integer.class,project));
    store.db.update("UPDATE insight_weekly_subscriptions SET next_due=? WHERE project_id=?",Timestamp.from(now.minusSeconds(1)),project);
    assertFalse(weekly.generate(project,now));
    var history=getJson(url("weekly"));assertEquals("queued",history.path("items").get(0).path("status").asText());
    String id=history.path("items").get(0).path("id").asText();assertTrue(getJson(url("weekly/reports/"+id)).path("archived").asBoolean());
    mvc.perform(get(url("weekly/reports/"+id)).cookie(otherCookie)).andExpect(status().isNotFound());
    Long outbox=store.db.queryForObject("SELECT outbox_id FROM insight_weekly_reports WHERE project_id=?",Long.class,project);
    String mail=store.secrets.open(store.db.queryForObject("SELECT body FROM outbox WHERE id=?",String.class,outbox));
    assertTrue(mail.contains(weekly.publicUrl+"/app/cost-insights?project="+project));
    assertTrue(mail.contains(weekly.publicUrl+"/app/weekly?project="+project));
    assertTrue(WeeklyInsights.mailEligible(store,outbox));
    store.db.update("UPDATE users SET email=? WHERE id=?","changed-"+user+"@test.local",user);
    assertFalse(WeeklyInsights.mailEligible(store,outbox));
    mvc.perform(post(url("weekly")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"enabled\":false,\"timezone\":\"UTC\"}")).andExpect(status().isOk());
    assertEquals("cancelled",getJson(url("weekly")).path("items").get(0).path("status").asText());
    assertEquals("",store.db.queryForObject("SELECT body FROM outbox WHERE id=?",String.class,outbox));
  }
  @Test void rollbackDoesNotLeaveAnOrphanEmailAndRecoveryQueuesExactlyOne() {
    Instant now=Instant.now();subscribe(now.minusSeconds(1));
    store.db.execute("CREATE FUNCTION reject_insight_report() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test rollback'; END $$");
    store.db.execute("CREATE TRIGGER reject_insight_report BEFORE INSERT ON insight_weekly_reports FOR EACH ROW EXECUTE FUNCTION reject_insight_report()");
    try {assertThrows(RuntimeException.class,()->weekly.generate(project,now));assertEquals(0,store.db.queryForObject("SELECT count(*) FROM outbox WHERE insight_project_id=?",Integer.class,project));}
    finally {store.db.execute("DROP TRIGGER reject_insight_report ON insight_weekly_reports");store.db.execute("DROP FUNCTION reject_insight_report()");}
    assertTrue(weekly.generate(project,now));
  }
  @Test void deletedProjectsAndDowngradesSuppressQueuedMailAndOrdinaryMailStillWorks() {
    Instant now=Instant.now();subscribe(now.minusSeconds(1));assertTrue(weekly.generate(project,now));
    Long id=store.db.queryForObject("SELECT outbox_id FROM insight_weekly_reports WHERE project_id=?",Long.class,project);
    store.db.update("UPDATE workspaces SET plan='Free' WHERE id=?",workspace);assertFalse(WeeklyInsights.mailEligible(store,id));
    store.db.update("UPDATE workspaces SET plan='Core' WHERE id=?",workspace);store.db.update("UPDATE projects SET deleted_at=now() WHERE id=?",project);assertFalse(WeeklyInsights.mailEligible(store,id));
    Long normal=store.db.queryForObject("INSERT INTO outbox(recipient,subject,body) VALUES('example@test.local','Example','example') RETURNING id",Long.class);
    assertTrue(WeeklyInsights.mailEligible(store,normal));
  }
  @Test void timezoneScheduleHandlesDstAndHasNoImmediateOptInMail() throws Exception {
    ZoneId zone=ZoneId.of("America/New_York");Instant sunday=Instant.parse("2026-03-08T13:00:00Z");
    assertEquals(Instant.parse("2026-03-09T13:00:00Z"),WeeklyInsights.nextDue(sunday,zone));
    assertEquals(Instant.parse("2026-03-02T05:00:00Z"),WeeklyInsights.weekEnd(sunday,zone));
    mvc.perform(get(url("weekly/preview?timezone=not-a-zone")).cookie(cookie)).andExpect(status().isBadRequest());
    assertEquals(WeeklyInsights.weekEnd(Instant.now(),ZoneId.of("UTC")).toString(),getJson(url("weekly/preview?timezone=UTC")).path("after").path("end").asText());
    mvc.perform(post(url("weekly")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"enabled\":true,\"timezone\":\"not-a-zone\"}")).andExpect(status().isBadRequest());
    mvc.perform(post(url("weekly")).cookie(cookie).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"enabled\":true,\"timezone\":\"Asia/Shanghai\"}")).andExpect(status().isOk());
    assertEquals(0,store.db.queryForObject("SELECT count(*) FROM outbox WHERE insight_project_id=?",Integer.class,project));
  }
}
