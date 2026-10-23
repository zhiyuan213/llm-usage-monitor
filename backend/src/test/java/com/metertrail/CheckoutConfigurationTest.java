package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CheckoutConfigurationTest {
  @Test void publicConfigurationOnlyContainsBrowserTokenAndRejectsMixedEnvironments() {
    var sandbox = new CheckoutConfiguration("test_example", "https://sandbox-api.paddle.com");
    assertEquals(java.util.Set.of("enabled", "environment", "token"), sandbox.config().keySet());
    assertEquals(true, sandbox.config().get("enabled"));
    assertEquals("sandbox", sandbox.config().get("environment"));
    assertEquals("test_example", sandbox.config().get("token"));
    for (var config : java.util.List.of(
        new CheckoutConfiguration("live_example", "https://sandbox-api.paddle.com"),
        new CheckoutConfiguration("test_example", "https://api.paddle.com"),
        new CheckoutConfiguration("test_example", "https://other.example"),
        new CheckoutConfiguration("", "https://sandbox-api.paddle.com"))) {
      assertEquals(false, config.config().get("enabled"));
      assertEquals("", config.config().get("token"));
    }
    assertTrue(new CheckoutConfiguration("live_example", "https://api.paddle.com").enabled);
  }
}
