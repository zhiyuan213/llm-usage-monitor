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
  void receiptEndsWithoutPollingAndQueryIsExplicit() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var bodies = new CopyOnWriteArrayList<String>();
    var results = new CopyOnWriteArrayList<String>();
    var queries = new CopyOnWriteArrayList<String>();
    server.createContext(
        "/",
        exchange -> {
          boolean write = exchange.getRequestMethod().equals("POST");
          if (!write) queries.add(exchange.getRequestURI().getPath());
          assertEquals("Bearer test", exchange.getRequestHeaders().getFirst("Authorization"));
          if (write) bodies.add(new String(exchange.getRequestBody().readAllBytes()));
          byte[] result =
              (write
                      ? "{\"batchId\":\"550e8400-e29b-41d4-a716-446655440099\",\"status\":\"pending\"}"
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
      assertTrue(results.get(0).contains("pending"));
      assertEquals(0, queries.size());
      var queried = sdk.getBatch("550e8400-e29b-41d4-a716-446655440099");
      assertEquals(200, queried.status());
      assertEquals("completed", queried.data().path("status").asText());
      assertEquals(1, queries.size());
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
  @Test
  void realHttpRetryAndAuthResumeDrainOnCloseWithoutChangingIdentity() throws Exception {
    for (int initial : new int[] {503, 401}) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      var bodies = new CopyOnWriteArrayList<String>();
      var results = new CopyOnWriteArrayList<String>();
      var errors = new CopyOnWriteArrayList<String>();
      var phase = new java.util.concurrent.atomic.AtomicInteger(initial);
      server.createContext("/v1/events/batch", exchange -> {
        assertEquals("Bearer synthetic", exchange.getRequestHeaders().getFirst("Authorization"));
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        int status = phase.getAndSet(202);
        byte[] response = (status == 202 ? "{\"batchId\":\"receipt\",\"status\":\"pending\"}" : "{\"code\":\"" + (status == 401 ? "INVALID_API_KEY" : "TEMPORARY_FAILURE") + "\"}").getBytes();
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response); exchange.close();
      });
      server.start();
      var sdk = new MeterTrail("http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", "http-test", r -> results.add(r.toString()), errors::add);
      try {
        var event = new ObjectMapper().createObjectNode().put("type", "trace").put("objectId", "one");
        assertTrue(sdk.record(event));
        if (initial == 401) {
          assertFalse(sdk.flush(Duration.ofSeconds(3)));
          assertEquals("INVALID_API_KEY", sdk.pausedReason());
          assertFalse(sdk.flush(Duration.ofSeconds(3)));
          assertEquals(1, bodies.size());
          sdk.resume();
        }
        sdk.close();
        assertEquals(2, bodies.size());
        assertEquals(bodies.get(0), bodies.get(1));
        assertEquals(1, results.size());
        assertFalse(sdk.record(event));
        assertEquals("CLOSED", errors.get(errors.size()-1));
      } finally {sdk.close(); server.stop(0);}
    }
  }
  @Test void timeoutRetainsUnconfirmedEventAndHealthyRetryRecoversIt() throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var healthy=new java.util.concurrent.atomic.AtomicBoolean(false);
    var bodies=new CopyOnWriteArrayList<String>();var results=new CopyOnWriteArrayList<String>();var errors=new CopyOnWriteArrayList<String>();
    server.createContext("/v1/events/batch",exchange->{
      bodies.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
      boolean ok=healthy.get();byte[] body=(ok?"{\"batchId\":\"recovered\",\"status\":\"pending\"}":"{\"code\":\"SERVICE_UNAVAILABLE\"}").getBytes();
      exchange.sendResponseHeaders(ok?202:503,body.length);exchange.getResponseBody().write(body);exchange.close();
    });server.start();
    var sdk=new MeterTrail("http://127.0.0.1:"+server.getAddress().getPort(),"synthetic","deadline",r->results.add(r.toString()),errors::add);
    try {
      sdk.record(new ObjectMapper().createObjectNode().put("type","trace").put("objectId","deadline"));
      assertFalse(sdk.flush(Duration.ofMillis(60)));
      assertTrue(results.isEmpty());assertTrue(errors.contains("FLUSH_TIMEOUT"));
      healthy.set(true);assertTrue(sdk.flush(Duration.ofSeconds(4)));
      assertEquals(1,results.size());assertTrue(bodies.size()>=1);
      for(String body:bodies)assertEquals(bodies.get(0),body);
    } finally {sdk.close();server.stop(0);}
  }
}
