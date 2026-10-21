package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProtocolTest {
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
