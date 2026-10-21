package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

final class Protocol {
  static void envelope(JsonNode b) {
    Support.require(
        b.isObject() && b.path("schemaVersion").asText().equals("1.0"), "INVALID_SCHEMA");
    JsonNode a = b.path("agent");
    for (String key : List.of("serviceName", "environment", "instanceId"))
      Support.text(a, key, 128);
    for (String k : List.of("sdkName", "sdkVersion")) if (a.has(k)) Support.text(a, k, 128);
    Support.require(
        b.path("events").isArray()
            && b.path("events").size() >= 1
            && b.path("events").size() <= 500,
        "INVALID_BATCH_SIZE");
  }

  static void event(JsonNode e) {
    Support.require(
        e.toString().getBytes(StandardCharsets.UTF_8).length <= 8192, "EVENT_TOO_LARGE");
    Set<String> allowed =
        Set.of(
            "eventId",
            "type",
            "objectId",
            "revision",
            "traceId",
            "spanId",
            "parentSpanId",
            "startedAt",
            "data");
    e.fieldNames().forEachRemaining(k -> Support.require(allowed.contains(k), "UNKNOWN_FIELD"));
    String id = Support.text(e, "eventId", 36);
    Support.require(
        id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"), "INVALID_EVENT_ID");
    String type = Support.text(e, "type", 16);
    Support.require(Set.of("trace", "observation", "score").contains(type), "INVALID_TYPE");
    Support.text(e, "objectId", 128);
    Support.require(
        e.path("revision").isIntegralNumber()
            && e.path("revision").canConvertToLong()
            && e.path("revision").asLong() > 0,
        "INVALID_REVISION");
    try {
      Instant started = Instant.parse(Support.text(e, "startedAt", 40));
      Support.require(!started.isAfter(Instant.now().plusSeconds(300)), "INVALID_TIMESTAMP");
    } catch (Exception x) {
      throw new ApiError(400, "INVALID_TIMESTAMP");
    }
    if (!type.equals("score"))
      Support.require(e.path("traceId").asText().matches("[0-9a-fA-F]{32}"), "INVALID_TRACE_ID");
    if (type.equals("observation")) {
      Support.require(e.path("spanId").asText().matches("[0-9a-fA-F]{16}"), "INVALID_SPAN_ID");
      if (e.hasNonNull("parentSpanId"))
        Support.require(
            e.path("parentSpanId").asText().matches("[0-9a-fA-F]{16}"), "INVALID_PARENT_SPAN");
    }
    JsonNode d = e.path("data");
    Support.require(d.isObject(), "INVALID_DATA");
    Set<String> fields =
        type.equals("score")
            ? Set.of("name", "targetType", "targetId", "valueType", "value")
            : Set.of(
                "name",
                "observationType",
                "durationMs",
                "provider",
                "model",
                "usage",
                "status",
                "error",
                "userId",
                "customerId",
                "feature");
    d.fieldNames().forEachRemaining(k -> Support.require(fields.contains(k), "UNKNOWN_DATA_FIELD"));
    if (type.equals("score")) {
      Support.text(d, "name", 128);
      Support.text(d, "targetId", 128);
      Support.require(
          Set.of("trace", "observation").contains(d.path("targetType").asText()),
          "INVALID_SCORE_TARGET");
      String vt = d.path("valueType").asText();
      JsonNode v = d.path("value");
      Support.require(
          (vt.equals("numeric") && v.isNumber() && Double.isFinite(v.asDouble()))
              || (vt.equals("boolean") && v.isBoolean())
              || (vt.equals("categorical")
                  && v.isTextual()
                  && !v.asText().isBlank()
                  && v.asText().length() <= 128),
          "INVALID_SCORE");
    } else {
      if (type.equals("trace")) Support.text(d, "name", 256);
      Support.require(
          Set.of("success", "error", "cancelled").contains(d.path("status").asText()),
          "INVALID_STATUS");
      nonnegative(d.path("durationMs"), false, "INVALID_DURATION");
      for (String k : List.of("userId", "customerId", "feature"))
        if (d.hasNonNull(k)) Support.text(d, k, 128);
      if (d.hasNonNull("error")) Support.text(d, "error", 1024);
      if (type.equals("observation")) {
        Support.require(
            Set.of("generation", "span", "tool", "retrieval", "embedding", "other")
                .contains(d.path("observationType").asText()),
            "INVALID_OBSERVATION_TYPE");
        if (d.path("observationType").asText().equals("generation")) {
          Support.text(d, "provider", 64);
          Support.text(d, "model", 256);
          Support.require(d.path("usage").isObject(), "INVALID_USAGE");
        }
        if (d.has("usage")) {
          JsonNode u = d.path("usage");
          u.fieldNames()
              .forEachRemaining(
                  k ->
                      Support.require(
                          Set.of("inputTokens", "outputTokens", "cachedInputTokens").contains(k),
                          "INVALID_USAGE"));
          for (String k : List.of("inputTokens", "outputTokens", "cachedInputTokens")) {
            Support.require(u.has(k), "INVALID_USAGE");
            nonnegative(u.get(k), true, "INVALID_USAGE");
          }
          if (!u.path("cachedInputTokens").isNull())
            Support.require(
                !u.path("inputTokens").isNull()
                    && u.path("cachedInputTokens").asLong() <= u.path("inputTokens").asLong(),
                "INVALID_USAGE");
        }
      }
    }
  }

  static void nonnegative(JsonNode n, boolean nullable, String code) {
    Support.require(
        (nullable && n.isNull())
            || (n.isIntegralNumber()
                && n.canConvertToLong()
                && n.asLong() >= 0
                && n.asLong() <= 9_000_000_000_000L),
        code);
  }
}
