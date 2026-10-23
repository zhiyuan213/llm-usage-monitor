package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

class SubscriptionChangesTest {
  HttpServer server;
  JdbcTemplate db;
  SubscriptionChanges changes;
  MockHttpServletRequest request;
  UUID user=UUID.randomUUID(), workspace=UUID.randomUUID(), quoteId=UUID.randomUUID();
  AtomicInteger writes=new AtomicInteger(), code=new AtomicInteger(200);
  AtomicReference<JsonNode> submitted=new AtomicReference<>();
  String preview="{\"recurring_transaction_details\":{\"totals\":{\"total\":\"19900\",\"currency_code\":\"USD\"}},\"immediate_transaction\":{\"details\":{\"totals\":{\"total\":\"17000\",\"currency_code\":\"USD\"}}},\"next_transaction\":null}";
  @BeforeEach void setup() throws Exception {
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/subscriptions/sub_test", exchange->{
      String result;
      int status=200;
      if(exchange.getRequestURI().getPath().endsWith("/preview")) {
        assertEquals("PATCH",exchange.getRequestMethod());
        result="{\"data\":"+preview+"}";
      } else if(exchange.getRequestMethod().equals("GET"))
        result="{\"data\":{\"id\":\"sub_test\",\"status\":\"active\",\"updated_at\":\"unchanged\",\"items\":[{\"price\":{\"id\":\"pri_core\"}}]}}";
      else {
        writes.incrementAndGet();
        submitted.set(Support.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
        status=code.get(); result=status==200 ? "{\"data\":{}}" : "{\"error\":{\"code\":\"payment_declined\"}}";
      }
      byte[] bytes=result.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");
      exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }); server.start();
    db=mock(JdbcTemplate.class);
    when(db.queryForList("SELECT * FROM workspaces WHERE owner_id=?",user)).thenReturn(List.of(Map.of("id",workspace,"subscription_id","sub_test")));
    var store=new Store(db,mock(Secrets.class));
    var payments=new PaymentsController(store,new SimpleMeterRegistry(),new CheckoutConfiguration("test_example","https://sandbox-api.paddle.com"),"https://example.test","test-secret","api-key","pri_core","pri_pro","http://127.0.0.1:"+server.getAddress().getPort());
    var factory=new org.springframework.aop.framework.ProxyFactory(payments);
    factory.setProxyTargetClass(true);
    factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
    var proxy=(PaymentsController)factory.getProxy();
    // Spring's constructor-bypassing proxy has empty fields; methods reach the initialized target.
    assertNull(proxy.core);
    assertEquals("pri_core",proxy.planPrice("Core"));
    changes=new SubscriptionChanges(proxy,store);
    request=new MockHttpServletRequest(); request.setAttribute("user",Map.of("id",user));
  }
  @AfterEach void stop(){server.stop(0);}
  void quote(String status, String values) {
    when(db.queryForList("SELECT * FROM subscription_change_quotes WHERE id=? AND workspace_id=?",quoteId,workspace))
      .thenReturn(List.of(Map.of("id",quoteId,"plan","Pro","subscription_id","sub_test","provider_updated_at","unchanged","preview",values,"expires_at",Timestamp.from(Instant.now().plusSeconds(300)),"status",status)));
    when(db.update("UPDATE subscription_change_quotes SET status='processing' WHERE id=? AND status='ready'",quoteId)).thenReturn(1,0);
  }
  JsonNode input(){return Support.parse("{\"quoteId\":\""+quoteId+"\"}");}
  @Test void previewUsesProviderCurrencyAndSupportsPatchWithoutChangingSubscription() {
    var result=changes.preview(Support.parse("{\"plan\":\"Pro\",\"price_id\":\"attacker\"}"),request);
    JsonNode totals=(JsonNode)result.get("totals");
    assertEquals("19900",totals.path("recurring_transaction_details").path("total").asText());
    assertEquals("17000",totals.path("immediate_transaction").path("total").asText());
    assertEquals(0,writes.get());
  }
  @Test void confirmsExistingSubscriptionOnceWithFailurePreventionAndNoLocalQuotaReset() {
    quote("ready",preview);
    assertEquals("pending",changes.change(input(),request).get("status"));
    assertEquals(1,writes.get());
    assertEquals("pri_pro",submitted.get().path("items").get(0).path("price_id").asText());
    assertEquals("prevent_change",submitted.get().path("on_payment_failure").asText());
    assertEquals("prorated_immediately",submitted.get().path("proration_billing_mode").asText());
    assertThrows(ApiError.class,()->changes.change(input(),request));
    assertEquals(1,writes.get());
    verify(db,never()).update(startsWith("UPDATE workspaces"),any(Object[].class));
  }
  @Test void changedAmountOrForeignQuoteCannotBeSubmitted() {
    quote("ready",preview.replace("17000","16999"));
    assertThrows(ApiError.class,()->changes.change(input(),request));
    when(db.queryForList("SELECT * FROM subscription_change_quotes WHERE id=? AND workspace_id=?",quoteId,workspace)).thenReturn(List.of());
    assertThrows(ApiError.class,()->changes.change(input(),request));
    assertEquals(0,writes.get());
  }
  @Test void providerPaymentRejectionDoesNotActivatePlan() {
    quote("ready",preview); code.set(402);
    assertThrows(ApiError.class,()->changes.change(input(),request));
    verify(db).update("UPDATE subscription_change_quotes SET status='failed' WHERE id=?",quoteId);
    verify(db,never()).update(startsWith("UPDATE workspaces"),any(Object[].class));
  }
  @Test void ambiguousProviderFailureRemainsClaimedRatherThanAutomaticallyChargingAgain() {
    quote("ready",preview); code.set(500);
    assertThrows(ApiError.class,()->changes.change(input(),request));
    verify(db,never()).update("UPDATE subscription_change_quotes SET status='failed' WHERE id=?",quoteId);
    assertThrows(ApiError.class,()->changes.change(input(),request));
    assertEquals(1,writes.get());
  }
}
