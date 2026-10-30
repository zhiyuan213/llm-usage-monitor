package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CreemEnvironmentTest {
  final CreemEnvironment legacy = new CreemEnvironment("old", "secret", "core", "pro", "url");

  @Test void selectsWholeEnvironmentWithoutFallback() {
    var env = new MockEnvironment().withProperty("CREEM_MODE", "test")
        .withProperty("CREEM_TEST_API_KEY", "creem_test_example")
        .withProperty("CREEM_TEST_WEBHOOK_SECRET", "test-secret")
        .withProperty("CREEM_TEST_CORE_PRODUCT", "test-core")
        .withProperty("CREEM_TEST_PRO_PRODUCT", "test-pro")
        .withProperty("CREEM_LIVE_API_KEY", "creem_live_example");
    assertEquals(new CreemEnvironment("creem_test_example", "test-secret", "test-core", "test-pro",
        "https://test-api.creem.io"), CreemEnvironment.select(env, legacy));
    env.setProperty("CREEM_MODE", "live");
    assertEquals(new CreemEnvironment("creem_live_example", "", "", "", "https://api.creem.io"),
        CreemEnvironment.select(env, legacy));
  }

  @Test void rejectsMixedKeysAndInvalidMode() {
    var env = new MockEnvironment().withProperty("CREEM_MODE", "live")
        .withProperty("CREEM_LIVE_API_KEY", "creem_test_example");
    assertThrows(IllegalArgumentException.class, () -> CreemEnvironment.select(env, legacy));
    env.setProperty("CREEM_MODE", "typo");
    assertThrows(IllegalArgumentException.class, () -> CreemEnvironment.select(env, legacy));
    assertEquals(legacy, CreemEnvironment.select(new MockEnvironment(), legacy));
  }
}
