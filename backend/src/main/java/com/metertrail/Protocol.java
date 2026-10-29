package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

final class Protocol {
  static final class InvalidEvents extends ApiError {
    final List<Map<String, Object>> errors;
    InvalidEvents(List<Map<String, Object>> errors) {
      super(400, (String) errors.get(0).get("code"));
      this.errors = List.copyOf(errors);
    }
  }

  /** Pure request validation; never queries state or accepts any part of a batch. */
  static void validateEvents(JsonNode body) {
    var errors = new ArrayList<Map<String, Object>>();
    int index = 0;
    for (JsonNode value : body.path("events")) {
      try {
        event(value);
      } catch (ApiError invalid) {
        String field = switch (invalid.code) {
          case "EVENT_ID_REQUIRED", "INVALID_EVENT_ID_TYPE", "EVENT_ID_LENGTH_MUST_BE_36", "INVALID_EVENT_ID_FORMAT" -> "eventId";
          case "INVALID_TYPE" -> "type";
          case "INVALID_OBJECTID" -> "objectId";
          case "INVALID_REVISION" -> "revision";
          case "INVALID_TIMESTAMP" -> "startedAt";
          case "INVALID_TRACE_ID" -> "traceId";
          case "INVALID_SPAN_ID" -> "spanId";
          case "INVALID_PARENT_SPAN" -> "parentSpanId";
          case "INVALID_DATA", "UNKNOWN_DATA_FIELD" -> "data";
          case "INVALID_DURATION" -> "data.durationMs";
          case "INVALID_OBSERVATION_TYPE" -> "data.observationType";
          case "INVALID_USAGE" -> "data.usage";
          case "INVALID_NAME" -> "data.name";
          case "INVALID_STATUS" -> "data.status";
          case "INVALID_PROVIDER" -> "data.provider";
          case "INVALID_MODEL" -> "data.model";
          case "INVALID_USERID" -> "data.userId";
          case "INVALID_CUSTOMERID" -> "data.customerId";
          case "INVALID_FEATURE" -> "data.feature";
          case "INVALID_ERROR" -> "data.error";
          default -> "";
        };
        errors.add(Map.of("index", index, "field", "events[" + index + "]" + (field.isEmpty() ? "" : "." + field), "code", invalid.code));
      }
      index++;
    }
    if (!errors.isEmpty()) throw new InvalidEvents(errors);
  }

  static void envelope(JsonNode b) {
    Support.require(
        b.isObject() && (!b.has("schemaVersion") || (b.path("schemaVersion").isTextual() && b.path("schemaVersion").asText().equals("1.0"))), "INVALID_SCHEMA");
    JsonNode a = b.path("agent");
    Support.require(!b.has("agent") || a.isObject(), "INVALID_AGENT");
    for (String key : List.of("serviceName", "environment", "instanceId"))
      if (a.has(key)) Support.text(a, key, 128);
    for (String k : List.of("sdkName", "sdkVersion")) if (a.has(k)) Support.text(a, k, 128);
    Support.require(
        b.path("events").isArray()
            && b.path("events").size() >= 1
            && b.path("events").size() <= 500,
        "INVALID_BATCH_SIZE");
  }

  static JsonNode defaults(JsonNode body) {
    envelope(body);
    var normalized = (com.fasterxml.jackson.databind.node.ObjectNode) body.deepCopy();
    if (!normalized.has("schemaVersion")) normalized.put("schemaVersion", "1.0");
    var agent = normalized.withObject("agent");
    for (String key : List.of("serviceName", "environment", "instanceId"))
      if (!agent.has(key)) agent.put(key, key + "-1");
    for (JsonNode event : normalized.path("events"))
      if (event.path("data") instanceof com.fasterxml.jackson.databind.node.ObjectNode data)
        ProviderUsage.normalize(data);
    return normalized;
  }

  static JsonNode eventDefaults(JsonNode event) {
    if (!event.isObject() || event.has("revision")) return event;
    var normalized = (com.fasterxml.jackson.databind.node.ObjectNode) event.deepCopy();
    normalized.put("revision", 1);
    return normalized;
  }

  // Keep legacy UUID spelling semantics while allowing arbitrary new 36-character IDs.
  static String eventIdentity(String id) {
    return id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
        ? id.toLowerCase(Locale.ROOT) : id;
  }

  static UUID legacyEventIdentity(String id) {
    return id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
        ? UUID.fromString(id) : UUID.randomUUID();
  }

  static void event(JsonNode e) {
    Support.require(e.isObject(), "INVALID_EVENT_TYPE");
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
    JsonNode eventId = e.get("eventId");
    Support.require(eventId != null && !eventId.isNull(), "EVENT_ID_REQUIRED");
    Support.require(eventId.isTextual(), "INVALID_EVENT_ID_TYPE");
    String id = eventId.asText();
    Support.require(!id.isBlank(), "EVENT_ID_REQUIRED");
    Support.require(id.codePointCount(0, id.length()) == 36, "EVENT_ID_LENGTH_MUST_BE_36");
    String type = Support.text(e, "type", 16);
    Support.require(Set.of("trace", "observation", "score").contains(type), "INVALID_TYPE");
    Support.text(e, "objectId", 128);
    Support.require(
        !e.has("revision") || (e.path("revision").isIntegralNumber()
            && e.path("revision").canConvertToLong()
            && e.path("revision").asLong() > 0),
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
                "providerUsage",
                "usageFormat",
                "billing",
                "status",
                "error",
                "userId",
                "customerId",
                "feature");
    d.fieldNames().forEachRemaining(k -> Support.require(fields.contains(k), "UNKNOWN_DATA_FIELD"));
    Support.require(d.has("providerUsage")==d.has("usageFormat"),"INVALID_USAGE");
    if(d.has("providerUsage")||d.has("billing"))Support.require(type.equals("observation")&&d.path("observationType").asText().equals("generation"),"INVALID_USAGE");
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
                          Set.of("inputTokens", "outputTokens", "cachedInputTokens", "cacheWrite5mTokens", "cacheWrite1hTokens", "reasoningTokens").contains(k),
                          "INVALID_USAGE"));
          for (String k : List.of("inputTokens", "outputTokens", "cachedInputTokens")) {
            Support.require(u.has(k), "INVALID_USAGE");
            nonnegative(u.get(k), true, "INVALID_USAGE");
          }
          for (String k : List.of("cacheWrite5mTokens", "cacheWrite1hTokens", "reasoningTokens"))
            if (u.has(k)) nonnegative(u.get(k), true, "INVALID_USAGE");
          if (u.path("reasoningTokens").isIntegralNumber() && u.path("outputTokens").isIntegralNumber())
            Support.require(u.path("reasoningTokens").asLong()<=u.path("outputTokens").asLong(),"INVALID_USAGE");
          if (u.path("inputTokens").isIntegralNumber()) {
            long parts=u.path("cachedInputTokens").asLong()+u.path("cacheWrite5mTokens").asLong()+u.path("cacheWrite1hTokens").asLong();
            Support.require(parts<=u.path("inputTokens").asLong(),"INVALID_USAGE");
          }
          if (!u.path("cachedInputTokens").isNull())
            Support.require(
                !u.path("inputTokens").isNull()
                    && u.path("cachedInputTokens").asLong() <= u.path("inputTokens").asLong(),
                "INVALID_USAGE");
        }
        if (d.has("billing")) PricingRules.context(d.path("billing"));
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
