package com.metertrail.example;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metertrail.example.model.BatchRequest;
import com.metertrail.example.model.Event;
import com.metertrail.example.model.ObservationData;
import com.metertrail.example.model.TraceData;
import com.metertrail.example.model.Usage;
import java.util.List;
import org.junit.jupiter.api.Test;

class SendTest {
    @Test
    void unknownUsageRetainsRequiredNullFieldsWhileOptionalEventFieldsAreOmitted() {
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Usage input = new Usage();
        input.setInputTokens(1500L);
        input.setOutputTokens(null);
        input.setCachedInputTokens(null);
        ObservationData data = new ObservationData();
        data.setUsage(input);
        Event<ObservationData> observation = new Event<>();
        observation.setData(data);
        Event<TraceData> trace = new Event<>();
        trace.setData(new TraceData());
        BatchRequest request = new BatchRequest();
        request.setSchemaVersion("1.0");
        request.setEvents(List.of(trace, observation));
        var body = mapper.valueToTree(request);
        var usage = body.path("events").get(1).path("data").path("usage");
        assertEquals(3, usage.size());
        assertEquals(1500, usage.path("inputTokens").asLong());
        assertTrue(usage.has("outputTokens") && usage.path("outputTokens").isNull());
        assertTrue(usage.has("cachedInputTokens") && usage.path("cachedInputTokens").isNull());
        assertFalse(body.path("events").get(0).has("spanId"));
        assertEquals("1.0", body.path("schemaVersion").asText());
    }
}
