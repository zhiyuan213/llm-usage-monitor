package com.metertrail.example;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SendTest {
    @Test
    void unknownUsageRetainsRequiredNullFieldsWhileOptionalEventFieldsAreOmitted() {
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var observation = new Send.Event<>("550e8400-e29b-41d4-a716-446655440000", 1,
                "observation", "model-call", "0123456789abcdef0123456789abcdef",
                "0123456789abcdef", "2026-10-28T00:00:00Z",
                new Send.ObservationData("Model response", "generation", "success", 2000,
                        "example", "example-model", new Send.Usage(1500L, null, null)));
        var trace = new Send.Event<>("550e8400-e29b-41d4-a716-446655440001", 1,
                "trace", "request", "0123456789abcdef0123456789abcdef", null,
                "2026-10-28T00:00:00Z", new Send.TraceData("Request", "success", 2100, null, null, null));
        var body = mapper.valueToTree(new Send.BatchRequest("1.0",
                new Send.Agent("document-api", "test", "demo-1"), List.of(trace, observation)));
        var usage = body.path("events").get(1).path("data").path("usage");
        assertEquals(3, usage.size());
        assertEquals(1500, usage.path("inputTokens").asLong());
        assertTrue(usage.has("outputTokens") && usage.path("outputTokens").isNull());
        assertTrue(usage.has("cachedInputTokens") && usage.path("cachedInputTokens").isNull());
        assertFalse(body.path("events").get(0).has("spanId"));
        assertEquals("1.0", body.path("schemaVersion").asText());
    }
}
