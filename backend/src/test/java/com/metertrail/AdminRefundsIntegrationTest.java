package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"metertrail.secret=test-secret-at-least-32-characters-long","metertrail.worker-enabled=false","metertrail.ingest.pipeline.enabled=false","metertrail.creem-url=https://api.creem.io","metertrail.secure-cookies=false"})
@AutoConfigureMockMvc @Testcontainers
class AdminRefundsIntegrationTest {
  @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);r.add("spring.datasource.password",pg::getPassword);}
  @Autowired MockMvc mvc; @Autowired Store store; @MockitoSpyBean CreemPayments creem; @MockitoBean RateLimits limits;
  ObjectMapper json=new ObjectMapper();Cookie admin,user;UUID workspace;String id,sub,csrf="refund-test-csrf";ObjectNode payment,subscription;
  Cookie account(boolean role){UUID uid=UUID.randomUUID(),wid=UUID.randomUUID();String token=Support.token();store.db.update("INSERT INTO users(id,email,verified,is_admin) VALUES(?,?,true,?)",uid,uid+"@test.local",role);store.db.update("INSERT INTO workspaces(id,owner_id,name) VALUES(?,?,'refund test')",wid,uid);store.db.update("INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1 hour')",Support.hash(token),uid,csrf);if(!role)workspace=wid;return new Cookie("mt_session",token);}
  @BeforeEach void setup(){admin=account(true);user=account(false);id="tran_"+UUID.randomUUID().toString().replace("-","");sub="sub_"+UUID.randomUUID().toString().replace("-","");store.db.update("UPDATE workspaces SET payment_provider='creem',plan='Core',customer_id='cust_Test',subscription_id=?,subscription_status='active',used_units=17 WHERE id=?",sub,workspace);store.db.update("INSERT INTO payments(id,workspace_id,status,amount,currency) VALUES(?,?,'completed',29,'USD')",id,workspace);
    payment=json.createObjectNode().put("id",id).put("mode","prod").put("status","paid").put("amount_paid",2900).put("currency","USD").put("created_at",Instant.now().minusSeconds(60).toEpochMilli()).put("customer","cust_Test").put("subscription",sub);
    subscription=json.createObjectNode().put("id",sub).put("mode","prod").put("status","active").put("last_transaction_id",id);
    doReturn(payment).when(creem).get("/v1/transactions?transaction_id="+id);doReturn(subscription).when(creem).get("/v1/subscriptions?subscription_id="+sub);
    doReturn(json.createObjectNode().put("status","succeeded")).when(creem).post(eq("/v1/refunds"),any());doReturn(subscription.deepCopy().put("status","canceled")).when(creem).post(eq("/v1/subscriptions/"+sub+"/cancel"),any());
  }
  String body(){return json.createObjectNode().put("confirmation",id).put("reason","Customer requested seven-day refund").put("amount",29).put("currency","USD").put("cancelSubscription",true).toString();}
  org.springframework.test.web.servlet.ResultActions submit()throws Exception{return mvc.perform(post("/api/admin/payments/"+id+"/refund").cookie(admin).header("X-CSRF-Token",csrf).contentType("application/json").content(body()));}
  @Test void permissionsCsrfAndAuthoritativePreview()throws Exception{
    mvc.perform(get("/api/admin/payments").cookie(user)).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/payments/"+id+"/refund-preview").cookie(user)).andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/payments/"+id+"/refund").cookie(user).header("X-CSRF-Token",csrf).contentType("application/json").content(body())).andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/payments/"+id+"/refund").cookie(admin).contentType("application/json").content(body())).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/payments/"+id+"/refund-preview").cookie(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(29)).andExpect(jsonPath("$.cancelSubscription").value(true));
    payment.put("customer","cust_Other");mvc.perform(get("/api/admin/payments/"+id+"/refund-preview").cookie(admin)).andExpect(status().isConflict());
    verify(creem,never()).post(eq("/v1/refunds"),any());
  }
  @Test void sevenDayWindowModeAmountAndNewerRenewalCannotBeBypassed()throws Exception{
    payment.put("created_at",Instant.now().minus(Duration.ofDays(8)).toEpochMilli());submit().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_WINDOW_EXPIRED"));
    payment.put("created_at",Instant.now().minusSeconds(60).toEpochMilli()).put("mode","test");submit().andExpect(status().isBadRequest());
    payment.put("mode","prod").put("amount_paid",3100);submit().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_QUOTE_CHANGED"));
    payment.put("amount_paid",2900);subscription.put("last_transaction_id","tran_Newer");submit().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_NEWER_PAYMENT_EXISTS"));verify(creem,never()).post(eq("/v1/refunds"),any());
  }
  @Test void confirmedRefundCancelsSubscriptionAndCannotBeSubmittedTwice()throws Exception{
    store.db.update("INSERT INTO billing_usage_periods(workspace_id,plan,period_start,period_end,used_units) VALUES(?,'Free',date_trunc('month',now()),date_trunc('month',now())+interval '1 month',1000)",workspace);
    submit().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("succeeded")).andExpect(jsonPath("$.cancellation_status").value("completed"));submit().andExpect(status().isOk());
    verify(creem,times(1)).post(eq("/v1/refunds"),any());verify(creem,times(1)).post(eq("/v1/subscriptions/"+sub+"/cancel"),any());
    assertEquals("canceled",store.db.queryForObject("SELECT subscription_status FROM workspaces WHERE id=?",String.class,workspace));assertEquals(1000L,store.db.queryForObject("SELECT used_units FROM workspaces WHERE id=?",Long.class,workspace));
    assertEquals(17L,store.db.queryForObject("SELECT used_units FROM billing_usage_periods WHERE workspace_id=? AND plan='Core'",Long.class,workspace));
    assertEquals("Free",store.db.queryForObject("SELECT plan FROM workspaces WHERE id=?",String.class,workspace));
    assertEquals(true,IngestionStatus.describe(store.db.queryForMap("SELECT * FROM workspaces WHERE id=?",workspace),0,false,false,Instant.now()).get("can_accept_new"));
    assertEquals(1L,store.db.queryForObject("SELECT count(*) FROM audit_logs WHERE action='payment.refund.request' AND target=?",Long.class,id));
  }
  @Test void uncertainProviderResponseIsNotResubmittedAndCanReconcile()throws Exception{
    doThrow(new org.springframework.web.client.ResourceAccessException("synthetic timeout")).when(creem).post(eq("/v1/refunds"),any());
    submit().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("unknown"));submit().andExpect(status().isOk());verify(creem,times(1)).post(eq("/v1/refunds"),any());
    payment.put("status","refunded");mvc.perform(post("/api/admin/payments/"+id+"/refund-status").cookie(admin).header("X-CSRF-Token",csrf).contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("succeeded"));
  }
  @Test void cancellationFailureIsVisibleAndReconciliationDoesNotRefundAgain()throws Exception{
    doThrow(new org.springframework.web.client.ResourceAccessException("synthetic timeout")).when(creem).post(eq("/v1/subscriptions/"+sub+"/cancel"),any());
    submit().andExpect(status().isOk()).andExpect(jsonPath("$.cancellation_status").value("unknown"));
    doReturn(subscription.deepCopy().put("status","canceled")).when(creem).post(eq("/v1/subscriptions/"+sub+"/cancel"),any());
    mvc.perform(post("/api/admin/payments/"+id+"/refund-status").cookie(admin).header("X-CSRF-Token",csrf).contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.cancellation_status").value("completed"));verify(creem,times(1)).post(eq("/v1/refunds"),any());
  }
  @Test void concurrentSubmissionsClaimExactlyOneProviderRequest()throws Exception{
    var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    try{Callable<Integer> call=()->{start.await();return submit().andReturn().getResponse().getStatus();};var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertEquals(200,a.get(30,TimeUnit.SECONDS));assertEquals(200,b.get(30,TimeUnit.SECONDS));verify(creem,times(1)).post(eq("/v1/refunds"),any());}finally{pool.shutdownNow();}
  }
  @Test void adminListSearchAndPaginationIncludeOtherAccountsWithoutGrantingThemAccess()throws Exception{
    for(int i=0;i<12;i++)store.db.update("INSERT INTO payments(id,workspace_id,status,amount,currency) VALUES(?,?,'completed',29,'USD')",id+"x"+i,workspace);
    mvc.perform(get("/api/admin/payments").cookie(admin).param("search",id).param("size","10")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(13)).andExpect(jsonPath("$.items.length()").value(10));
    mvc.perform(get("/api/admin/payments").cookie(admin).param("search",id).param("size","10").param("page","1")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
    mvc.perform(get("/api/admin/payments").cookie(admin).param("search","absent-payment")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
  }
  @Test void partialRefundAndMissingConfirmationNeverReachProvider()throws Exception{
    mvc.perform(post("/api/admin/payments/"+id+"/refund").cookie(admin).header("X-CSRF-Token",csrf).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
    payment.put("refunded_amount",100);submit().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_REFUNDABLE"));
    verify(creem,never()).post(eq("/v1/refunds"),any());
  }
  org.springframework.web.client.RestClientResponseException providerError(int status,String body) {
    return new org.springframework.web.client.RestClientResponseException("provider error",status,"provider error",org.springframework.http.HttpHeaders.EMPTY,body.getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.charset.StandardCharsets.UTF_8);
  }
  @Test void rejectedRequestRetainsSafeDiagnosticsAndFailedSubmissionAuditWithoutResubmitting()throws Exception {
    doThrow(providerError(403,"{\"code\":\"INSUFFICIENT_PERMISSIONS\",\"message\":\"secret creem_live_test customer@example.com\"}")).when(creem).post(eq("/v1/refunds"),any());
    submit().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("rejected")).andExpect(jsonPath("$.provider_http_status").value(403)).andExpect(jsonPath("$.provider_error_code").value("INSUFFICIENT_PERMISSIONS")).andExpect(jsonPath("$.provider_error_stage").value("submission"));
    submit().andExpect(status().isOk());verify(creem,times(1)).post(eq("/v1/refunds"),any());
    var saved=store.db.queryForMap("SELECT * FROM admin_refunds WHERE transaction_id=?",id);
    assertEquals("secret [redacted] [redacted]",saved.get("provider_error_message"));assertNotNull(saved.get("provider_error_request_id"));
    assertFalse(saved.toString().contains("creem_live_test"));assertFalse(saved.toString().contains("customer@example.com"));
    assertEquals(false,store.db.queryForObject("SELECT success FROM audit_logs WHERE action='payment.refund.submission' AND target=?",Boolean.class,id));
    mvc.perform(get("/api/admin/payments").cookie(admin).param("search",id)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].provider_http_status").value(403));
  }
  @Test void ambiguousHttpAndUnstructuredErrorsRemainUnknownAndAreNotResubmitted()throws Exception {
    doThrow(providerError(429,"<html>private provider details</html>")).when(creem).post(eq("/v1/refunds"),any());
    submit().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("unknown")).andExpect(jsonPath("$.provider_error_code").value("HTTP_429"));
    submit().andExpect(status().isOk());verify(creem,times(1)).post(eq("/v1/refunds"),any());
  }
  @Test void externallyCompletedRefundReconcilesRejectedRequestWithoutIssuingAnotherRefund()throws Exception {
    doThrow(providerError(403,"{}")).when(creem).post(eq("/v1/refunds"),any());submit().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("rejected"));
    payment.put("status","refunded");subscription.put("status","canceled");
    mvc.perform(post("/api/admin/payments/"+id+"/refund-status").cookie(admin).header("X-CSRF-Token",csrf).contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("succeeded")).andExpect(jsonPath("$.cancellation_status").value("completed"));
    verify(creem,times(1)).post(eq("/v1/refunds"),any());verify(creem,never()).post(eq("/v1/subscriptions/"+sub+"/cancel"),any());
    assertEquals("Free",store.db.queryForObject("SELECT plan FROM workspaces WHERE id=?",String.class,workspace));
  }
  @Test void reconciliationAndCancellationFailuresExposeTheirIndependentStages()throws Exception {
    doThrow(providerError(403,"{\"error\":{\"code\":\"PERMISSION_DENIED\"}}")).when(creem).post(eq("/v1/subscriptions/"+sub+"/cancel"),any());
    submit().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("succeeded")).andExpect(jsonPath("$.cancellation_status").value("unknown")).andExpect(jsonPath("$.provider_error_stage").value("cancellation")).andExpect(jsonPath("$.provider_error_code").value("PERMISSION_DENIED"));
    doThrow(providerError(503,"unavailable")).when(creem).get("/v1/transactions?transaction_id="+id);
    mvc.perform(post("/api/admin/payments/"+id+"/refund-status").cookie(admin).header("X-CSRF-Token",csrf).contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.provider_error_stage").value("reconciliation")).andExpect(jsonPath("$.provider_http_status").value(503));
  }

}
