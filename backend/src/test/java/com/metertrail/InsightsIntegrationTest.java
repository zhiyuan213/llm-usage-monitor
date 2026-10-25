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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
  @MockBean RateLimits limits;
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
