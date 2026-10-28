package com.metertrail.example;

import com.metertrail.example.model.Agent;
import com.metertrail.example.model.BatchReceipt;
import com.metertrail.example.model.BatchRequest;
import com.metertrail.example.model.Event;
import com.metertrail.example.model.ObservationData;
import com.metertrail.example.model.TraceData;
import com.metertrail.example.model.Usage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class Send {
    public static void main(String[] args) throws Exception {
        Agent agent = new Agent();
        agent.setServiceName("document-api");
        agent.setEnvironment("test");
        agent.setInstanceId("demo-1");

        TraceData traceData = new TraceData();
        traceData.setName("Document analysis");
        traceData.setStatus("success");
        traceData.setDurationMs(2100L);
        traceData.setUserId("demo-user");
        traceData.setCustomerId("demo-customer");
        traceData.setFeature("document-analysis");

        Usage usage = new Usage();
        usage.setInputTokens(1500L);
        usage.setOutputTokens(400L);
        usage.setCachedInputTokens(0L); // Use null when unknown; 0 means no cache hits.

        ObservationData observationData = new ObservationData();
        observationData.setName("Model response");
        observationData.setObservationType("generation");
        observationData.setStatus("success");
        observationData.setDurationMs(2000L);
        observationData.setProvider("example");
        observationData.setModel("example-model");
        observationData.setUsage(usage);

        String traceId = UUID.randomUUID().toString().replace("-", "");
        String startedAt = Instant.now().toString(); // Replace with the measured request start.
        Event<TraceData> trace = new Event<>();
        trace.setEventId(UUID.randomUUID().toString());
        trace.setRevision(1L);
        trace.setType("trace");
        trace.setObjectId("trace-" + traceId);
        trace.setTraceId(traceId);
        trace.setStartedAt(startedAt);
        trace.setData(traceData);

        Event<ObservationData> observation = new Event<>();
        observation.setEventId(UUID.randomUUID().toString());
        observation.setRevision(1L);
        observation.setType("observation");
        observation.setObjectId("obs-" + traceId + "-1");
        observation.setTraceId(traceId);
        observation.setSpanId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        observation.setStartedAt(startedAt);
        observation.setData(observationData);

        BatchRequest batch = new BatchRequest();
        batch.setSchemaVersion("1.0");
        batch.setAgent(agent);
        batch.setEvents(List.of(trace, observation));

        MeterTrailClient client = new MeterTrailClient(
                requiredEnv("METERTRAIL_ENDPOINT"), requiredEnv("METERTRAIL_API_KEY"));
        // If retrying, reuse this batch unchanged, including every ID and timestamp.
        BatchReceipt receipt = client.sendBatch(batch);
        System.out.println("202 " + receipt.getStatus() + " batchId=" + receipt.getBatchId());
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name);
        return value;
    }
}
