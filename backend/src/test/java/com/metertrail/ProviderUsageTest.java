package com.metertrail;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ProviderUsageTest {
  ObjectNode adapt(String format,String raw) {
    var d=ModelPricing.JSON.createObjectNode();d.put("usageFormat",format);d.set("providerUsage",Support.parse(raw));
    ProviderUsage.normalize(d);return d;
  }
  @Test void openaiReasoningIsAlreadyOutputAndNormalizationIsIdempotent() {
    var d=adapt("openai","{\"prompt_tokens\":100,\"completion_tokens\":80,\"prompt_tokens_details\":{\"cached_tokens\":20},\"completion_tokens_details\":{\"reasoning_tokens\":60}}");
    assertEquals(80,d.path("usage").path("outputTokens").asInt());
    ProviderUsage.normalize(d);assertEquals(60,d.path("usage").path("reasoningTokens").asInt());
  }
  @Test void anthropicIncludesReadsAndWritesInNormalizedInput() {
    var d=adapt("anthropic","{\"input_tokens\":10,\"output_tokens\":40,\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":50,\"cache_creation\":{\"ephemeral_5m_input_tokens\":20,\"ephemeral_1h_input_tokens\":30}}");
    assertEquals(160,d.path("usage").path("inputTokens").asInt());
    assertEquals(30,d.path("usage").path("cacheWrite1hTokens").asInt());
    assertTrue(adapt("anthropic","{\"input_tokens\":10,\"output_tokens\":40,\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":50}").path("usage").path("cacheWrite5mTokens").isNull());
  }
  @Test void geminiAddsThoughtsExactlyOnceAndMissingIsUnknown() {
    var d=adapt("gemini","{\"promptTokenCount\":100,\"candidatesTokenCount\":20,\"thoughtsTokenCount\":30,\"cachedContentTokenCount\":0}");
    assertEquals(50,d.path("usage").path("outputTokens").asInt());
    assertTrue(adapt("openai","{\"prompt_tokens\":10,\"completion_tokens\":20}").path("usage").path("cachedInputTokens").isNull());
  }
  @Test void rejectContentNegativeUnknownAndConflictingCanonicalUsage() {
    assertThrows(ApiError.class,()->adapt("openai","{\"messages\":\"secret\"}"));
    assertThrows(ApiError.class,()->adapt("openai","{\"prompt_tokens\":-1}"));
    var d=adapt("openai","{\"prompt_tokens\":10,\"completion_tokens\":20}");
    ((ObjectNode)d.path("usage")).put("outputTokens",21);
    assertThrows(ApiError.class,()->ProviderUsage.normalize(d));
  }
}
