package com.metertrail;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CheckoutConfiguration {
  final String token;
  final String environment;
  final boolean enabled;

  CheckoutConfiguration(
      @Value("${metertrail.paddle-client-token:}") String token,
      @Value("${metertrail.paddle-url}") String apiUrl) {
    this.token = token;
    environment = "https://sandbox-api.paddle.com".equals(apiUrl) ? "sandbox" : "production";
    enabled = ("https://sandbox-api.paddle.com".equals(apiUrl) && token.matches("test_[a-zA-Z0-9]+"))
        || ("https://api.paddle.com".equals(apiUrl) && token.matches("live_[a-zA-Z0-9]+"));
  }

  @GetMapping("/v1/checkout/config")
  Map<String, Object> config() {
    // Only this browser token is public. Never return API keys or webhook secrets.
    return Map.of("enabled", enabled, "environment", environment, "token", enabled ? token : "");
  }
}
