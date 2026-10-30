package com.metertrail;

import org.springframework.core.env.Environment;

/** Select a complete credential set; explicit modes never fall back to legacy credentials. */
record CreemEnvironment(String key, String secret, String core, String pro, String url) {
  static CreemEnvironment select(Environment env, CreemEnvironment legacy) {
    String mode = env.getProperty("CREEM_MODE", "legacy").trim().toLowerCase(java.util.Locale.ROOT);
    if (mode.equals("legacy")) return legacy;
    if (!mode.equals("test") && !mode.equals("live"))
      throw new IllegalArgumentException("CREEM_MODE must be test, live or legacy");
    String prefix = "CREEM_" + mode.toUpperCase(java.util.Locale.ROOT) + "_";
    String key = env.getProperty(prefix + "API_KEY", "");
    if (!key.isBlank() && (mode.equals("test") != key.startsWith("creem_test_")))
      throw new IllegalArgumentException("Creem API key does not match CREEM_MODE");
    return new CreemEnvironment(key, env.getProperty(prefix + "WEBHOOK_SECRET", ""),
        env.getProperty(prefix + "CORE_PRODUCT", ""), env.getProperty(prefix + "PRO_PRODUCT", ""),
        mode.equals("test") ? "https://test-api.creem.io" : "https://api.creem.io");
  }
}
