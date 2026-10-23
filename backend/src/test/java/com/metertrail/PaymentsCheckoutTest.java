package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

class PaymentsCheckoutTest {
  @Test void createsServerSelectedPriceAndBindsReceiptToWorkspaceWithoutActivatingPlan() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var submitted = new AtomicReference<JsonNode>();
    var authorization = new AtomicReference<String>();
    server.createContext("/transactions", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      submitted.set(Support.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      byte[] result = "{\"data\":{\"id\":\"txn_synthetic\",\"checkout\":{\"url\":\"https://metertrail.test/pay?_ptxn=txn_synthetic\"}}}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(201, result.length);
      exchange.getResponseBody().write(result);
      exchange.close();
    });
    server.start();
    try {
      UUID user = UUID.randomUUID(), workspace = UUID.randomUUID();
      JdbcTemplate db = mock(JdbcTemplate.class);
      when(db.queryForList(anyString(), eq(user))).thenReturn(List.of(Map.of("id", workspace)));
      Store store = new Store(db, mock(Secrets.class));
      var controller = new PaymentsController(store, new SimpleMeterRegistry(),
          new CheckoutConfiguration("test_example", "https://sandbox-api.paddle.com"),
          "https://metertrail.test", "synthetic-secret", "synthetic-api-key", "pri_core", "pri_pro",
          "http://127.0.0.1:" + server.getAddress().getPort());
      var request = new MockHttpServletRequest();
      request.setAttribute("user", Map.of("id", user));
      var response = controller.checkout(Support.parse("{\"plan\":\"Core\",\"price_id\":\"attacker_price\"}"), request);
      assertEquals("https://metertrail.test/pay?_ptxn=txn_synthetic", response.get("url"));
      assertEquals("Bearer synthetic-api-key", authorization.get());
      JsonNode body = submitted.get();
      assertEquals("pri_core", body.path("items").get(0).path("price_id").asText());
      assertEquals(1, body.path("items").get(0).path("quantity").asInt());
      assertEquals("https://metertrail.test/pay", body.path("checkout").path("url").asText());
      UUID intent = UUID.fromString(body.path("custom_data").path("metertrail_intent").asText());
      verify(db).update("INSERT INTO checkout_intents(id,workspace_id,plan) VALUES(?,?,?)", intent, workspace, "Core");
      verify(db).update("UPDATE checkout_intents SET transaction_id=? WHERE id=?", "txn_synthetic", intent);
      verify(db).queryForList("SELECT * FROM workspaces WHERE owner_id=?", user);
      verifyNoMoreInteractions(db);
    } finally { server.stop(0); }
  }
}
