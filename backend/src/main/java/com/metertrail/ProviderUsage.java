package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Only numerical usage metadata is accepted, never provider responses or message content. */
final class ProviderUsage {
  private ProviderUsage() {}
  static void normalize(ObjectNode data) {
    if (!data.has("providerUsage")) return;
    JsonNode raw = data.path("providerUsage");
    Support.require(raw.isObject(), "INVALID_USAGE");
    String format = data.path("usageFormat").asText();
    Set<String> keys = switch (format) {
      case "openai" -> Set.of("prompt_tokens", "completion_tokens", "total_tokens", "prompt_tokens_details", "completion_tokens_details");
      case "anthropic" -> Set.of("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens", "cache_creation");
      case "gemini" -> Set.of("promptTokenCount", "candidatesTokenCount", "thoughtsTokenCount", "cachedContentTokenCount", "totalTokenCount");
      default -> throw new ApiError(400, "INVALID_USAGE");
    };
    raw.fieldNames().forEachRemaining(k -> Support.require(keys.contains(k), "INVALID_USAGE"));
    numbers(raw);
    ObjectNode u = ModelPricing.JSON.createObjectNode();
    switch (format) {
      case "openai" -> {
        nested(raw, "prompt_tokens_details", Set.of("cached_tokens"));
        nested(raw, "completion_tokens_details", Set.of("reasoning_tokens"));
        put(u,"inputTokens",raw.get("prompt_tokens")); put(u,"outputTokens",raw.get("completion_tokens"));
        put(u,"cachedInputTokens",raw.path("prompt_tokens_details").get("cached_tokens"));
        if(raw.path("completion_tokens_details").has("reasoning_tokens"))
          put(u,"reasoningTokens",raw.path("completion_tokens_details").get("reasoning_tokens"));
      }
      case "anthropic" -> {
        nested(raw,"cache_creation",Set.of("ephemeral_5m_input_tokens","ephemeral_1h_input_tokens"));
        put(u,"inputTokens",sum(raw,"input_tokens","cache_read_input_tokens","cache_creation_input_tokens"));
        put(u,"outputTokens",raw.get("output_tokens")); put(u,"cachedInputTokens",raw.get("cache_read_input_tokens"));
        // An aggregate write count does not identify its TTL; never assume a cheap TTL.
        JsonNode creation=raw.path("cache_creation");
        put(u,"cacheWrite5mTokens",creation.get("ephemeral_5m_input_tokens"));
        put(u,"cacheWrite1hTokens",creation.get("ephemeral_1h_input_tokens"));
        if(raw.path("cache_creation_input_tokens").isIntegralNumber() && raw.path("cache_creation_input_tokens").asLong()==0) {
          u.put("cacheWrite5mTokens",0); u.put("cacheWrite1hTokens",0);
        }
        if(creation.isObject() && u.path("cacheWrite5mTokens").isIntegralNumber() && u.path("cacheWrite1hTokens").isIntegralNumber())
          Support.require(Math.addExact(u.path("cacheWrite5mTokens").asLong(),u.path("cacheWrite1hTokens").asLong())==raw.path("cache_creation_input_tokens").asLong(-1),"INVALID_USAGE");
      }
      case "gemini" -> {
        put(u,"inputTokens",raw.get("promptTokenCount"));
        // Gemini reports thoughts separately; OpenAI completion_tokens already includes them.
        put(u,"outputTokens",sum(raw,"candidatesTokenCount","thoughtsTokenCount"));
        put(u,"cachedInputTokens",raw.get("cachedContentTokenCount"));
        put(u,"reasoningTokens",raw.get("thoughtsTokenCount"));
      }
    }
    if(data.has("usage")) Support.require(Support.parse(data.get("usage").toString()).equals(Support.parse(u.toString())),"INVALID_USAGE");
    data.set("usage",u);
  }
  static void numbers(JsonNode node) {
    if(node.isObject()) {node.elements().forEachRemaining(ProviderUsage::numbers);return;}
    Protocol.nonnegative(node,true,"INVALID_USAGE");
  }
  static void nested(JsonNode raw,String key,Set<String> keys) {
    if(!raw.has(key))return;
    Support.require(raw.path(key).isObject(),"INVALID_USAGE");
    raw.path(key).fieldNames().forEachRemaining(k->Support.require(keys.contains(k),"INVALID_USAGE"));
  }
  static JsonNode sum(JsonNode raw,String... keys) {
    long sum=0;
    for(String k:keys) {
      JsonNode n=raw.path(k);
      if(!n.isIntegralNumber()||!n.canConvertToLong()||n.asLong()<0)return ModelPricing.JSON.nullNode();
      try {sum=Math.addExact(sum,n.asLong());}catch(ArithmeticException ex){throw new ApiError(400,"INVALID_USAGE");}
    }
    return ModelPricing.JSON.getNodeFactory().numberNode(sum);
  }
  static void put(ObjectNode u,String key,JsonNode value) {u.set(key,value==null?ModelPricing.JSON.nullNode():value);}
}
