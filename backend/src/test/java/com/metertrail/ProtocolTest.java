package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolTest {
  @Test
  void preflightRejectsScalarEventsWithoutLeakingPayload() {
    var invalid = assertThrows(Protocol.InvalidEvents.class,
        () -> Protocol.validateEvents(Support.parse("{\"events\":[null,1,\"private-content\"]}")));
    assertEquals(3, invalid.errors.size());
    assertEquals("INVALID_EVENT_TYPE", invalid.code);
    assertFalse(invalid.errors.toString().contains("private-content"));
  }
  @Test
  void eventIdErrorsExplainTheExactInvalidContract() {
    var e = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    assertEquals("EVENT_ID_REQUIRED", assertThrows(ApiError.class, () -> Protocol.event(e)).code);
    e.putNull("eventId");
    assertEquals("EVENT_ID_REQUIRED", assertThrows(ApiError.class, () -> Protocol.event(e)).code);
    e.put("eventId", 123);
    assertEquals("INVALID_EVENT_ID_TYPE", assertThrows(ApiError.class, () -> Protocol.event(e)).code);
    e.put("eventId", " ");
    assertEquals("EVENT_ID_REQUIRED", assertThrows(ApiError.class, () -> Protocol.event(e)).code);
    for (String id : List.of("x".repeat(35), "x".repeat(37), "550e8400e29b41d4a716446655440000")) {
      e.put("eventId", id);
      assertEquals("EVENT_ID_LENGTH_MUST_BE_36", assertThrows(ApiError.class, () -> Protocol.event(e)).code);
    }
    e.put("type", "trace"); e.put("objectId", "example"); e.put("revision", 1);
    e.put("traceId", "0123456789abcdef0123456789abcdef"); e.put("startedAt", Instant.now().toString());
    e.set("data", Support.parse("{\"name\":\"Example\",\"status\":\"success\",\"durationMs\":1}"));
    for (String id : List.of("550e8400-e29b-41d4-a716-446655440000", "550E8400-E29B-41D4-A716-446655440000", "x".repeat(36), "中".repeat(36), "😀".repeat(36))) {
      e.put("eventId", id);
      assertDoesNotThrow(() -> Protocol.event(e));
    }
  }
  @Test
  void optionalDefaultsDoNotHideInvalidExplicitValues() {
    var input = Support.parse("{\"events\":[{}]}");
    var normalized = Protocol.defaults(input);
    assertEquals("1.0", normalized.path("schemaVersion").asText());
    for (String key : List.of("serviceName", "environment", "instanceId"))
      assertEquals(key + "-1", normalized.path("agent").path(key).asText());
    assertFalse(input.has("agent"));
    assertEquals(1, Protocol.eventDefaults(Support.parse("{}")).path("revision").asInt());
    for (String invalid : List.of("{\"schemaVersion\":null,\"events\":[{}]}", "{\"schemaVersion\":1.0,\"events\":[{}]}", "{\"agent\":null,\"events\":[{}]}", "{\"agent\":{\"serviceName\":null},\"events\":[{}]}"))
      assertThrows(ApiError.class, () -> Protocol.defaults(Support.parse(invalid)));
    var e = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    e.put("eventId", "r".repeat(36)); e.put("type", "trace"); e.put("objectId", "defaults");
    e.put("traceId", "0123456789abcdef0123456789abcdef"); e.put("startedAt", Instant.now().toString());
    e.set("data", Support.parse("{\"name\":\"test\",\"status\":\"success\",\"durationMs\":1}"));
    assertDoesNotThrow(() -> Protocol.event(e));
    for (String value : List.of("null", "0", "-1", "1.5", "\"1\"")) {
      e.set("revision", Support.parse(value));
      assertEquals("INVALID_REVISION", assertThrows(ApiError.class, () -> Protocol.event(e)).code);
    }
  }
  @Test
  void paymentCurrencyPrecision() {
    assertEquals(new BigDecimal("29.00"), PaymentsController.fromMinorUnits("2900", "USD"));
    assertEquals(new BigDecimal("2900"), PaymentsController.fromMinorUnits("2900", "JPY"));
  }

  @Test
  void progressivePricing() {
    assertEquals(0, Billing.overage(100_000).compareTo(BigDecimal.ZERO));
    assertEquals(0, Billing.overage(500_000).compareTo(new BigDecimal("32")));
    assertEquals(0, Billing.overage(1_100_000).compareTo(new BigDecimal("79")));
    assertEquals(0, Billing.overage(51_000_000).compareTo(new BigDecimal("3362")));
  }

  @Test
  void canonicalKeyOrder() {
    assertEquals(
        Support.canonical(Support.parse("{\"b\":1,\"a\":{\"z\":2,\"b\":3}}")),
        Support.canonical(Support.parse("{\"a\":{\"b\":3,\"z\":2},\"b\":1}")));
  }

  @Test
  void validatesPaddleSignatureAndTimestamp() {
    String body = "{\"a\":1}",
        secret = "test-secret",
        ts = String.valueOf(Instant.now().getEpochSecond());
    String header = "ts=" + ts + ";h1=" + Support.hmac(secret, ts + ":" + body);
    assertTrue(PaymentsController.signature(secret, header, body, Instant.now()));
    assertFalse(PaymentsController.signature(secret, header, body + " ", Instant.now()));
    assertFalse(PaymentsController.signature(secret, header, body, Instant.now().plusSeconds(301)));
  }

  @Test
  void encryptedOutbox() {
    Secrets s = new Secrets("a-test-secret-with-at-least-32-characters");
    String encrypted = s.seal("Code: 123456");
    assertFalse(encrypted.contains("123456"));
    assertEquals("Code: 123456", s.open(encrypted));
    assertNotEquals(encrypted, s.seal("Code: 123456"));
  }
}
