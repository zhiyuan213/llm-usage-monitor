package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HttpJsonCompatibilityTest {
  @Test void outboundClientsRoundTripProtocolJsonNodes() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      JsonNode payload = Support.parse("{\"data\":{\"id\":\"synthetic-transaction\",\"amount\":2900,\"active\":true}}");
      JsonNode response = Support.restClient().build().post()
          .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/")
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .body(payload).retrieve().body(JsonNode.class);
      assertEquals(payload, response);
      assertEquals(2900, response.path("data").path("amount").asInt());
    } finally { server.stop(0); }
  }
}
