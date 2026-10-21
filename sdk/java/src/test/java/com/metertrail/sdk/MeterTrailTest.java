package com.metertrail.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class MeterTrailTest {
  @Test
  void asynchronousRecordClonesInputAndPollsFinalResult() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var bodies = new CopyOnWriteArrayList<String>();
    var results = new CopyOnWriteArrayList<String>();
    server.createContext(
        "/",
        exchange -> {
          boolean write = exchange.getRequestMethod().equals("POST");
          if (write) bodies.add(new String(exchange.getRequestBody().readAllBytes()));
          byte[] result =
              (write
                      ? "{\"batchId\":\"test\"}"
                      : "{\"status\":\"completed\",\"accepted\":1,\"errors\":[]}")
                  .getBytes();
          exchange.sendResponseHeaders(write ? 202 : 200, result.length);
          exchange.getResponseBody().write(result);
          exchange.close();
        });
    server.start();
    try (var sdk =
        new MeterTrail(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "test",
            "test",
            r -> results.add(r.toString()),
            e -> {})) {
      var event = new ObjectMapper().createObjectNode();
      event.put("type", "trace").put("objectId", "one");
      event.putObject("data").put("name", "original");
      assertTrue(sdk.record(event));
      event.put("objectId", "mutated");
      assertTrue(sdk.flush(Duration.ofSeconds(5)));
      assertEquals(1, bodies.size());
      assertTrue(bodies.get(0).contains("\"objectId\":\"one\""));
      assertEquals(1, results.size());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void invalidAndOversizeRecordsReportErrors() throws Exception {
    var errors = new CopyOnWriteArrayList<String>();
    try (var sdk = new MeterTrail("http://localhost:1", "test", "test", r -> {}, errors::add)) {
      assertFalse(sdk.record(null));
      var event = new ObjectMapper().createObjectNode().put("text", "x".repeat(9000));
      assertFalse(sdk.record(event));
      assertEquals(java.util.List.of("INVALID_EVENT", "EVENT_TOO_LARGE"), errors);
    }
  }
}
