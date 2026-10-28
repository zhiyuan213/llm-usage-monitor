package com.metertrail.example;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class Send {
    public record Agent(String serviceName, String environment, String instanceId) {}
    public record BatchRequest(String schemaVersion, Agent agent, List<Event<?>> events) {}
    public record Event<T>(String eventId, long revision, String type, String objectId,
                           String traceId, String spanId, String startedAt, T data) {}
    public record TraceData(String name, String status, long durationMs,
                            String userId, String customerId, String feature) {}
    public record ObservationData(String name, String observationType, String status,
                                  long durationMs, String provider, String model, Usage usage) {}
    // Null means unknown; 0 means a measured zero, including no cache hits.
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Usage(Long inputTokens, Long outputTokens, Long cachedInputTokens) {}

    public static void main(String[] args) throws Exception {
        String endpoint = requiredEnv("METERTRAIL_ENDPOINT");
        String apiKey = requiredEnv("METERTRAIL_API_KEY");
        String traceId = newId().replace("-", "");
        String startedAt = Instant.now().toString();

        Event<TraceData> trace = new Event<>(newId(), 1, "trace", newId(),
                traceId, null, startedAt,
                new TraceData("Document analysis", "success", 2100,
                        "demo-user", "demo-customer", "document-analysis"));
        Event<ObservationData> observation = new Event<>(newId(), 1, "observation", newId(),
                traceId, newId().replace("-", "").substring(0, 16), startedAt,
                new ObservationData("Model response", "generation", "success", 2000,
                        "example", "example-model", new Usage(1500L, 400L, 0L)));
        BatchRequest batch = new BatchRequest("1.0", new Agent("document-api", "test", "demo-1"),
                List.of(trace, observation));

        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        byte[] payload = mapper.writeValueAsBytes(batch);
        // For retries, reuse these exact bytes and IDs instead of creating another batch.
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/v1/events/batch"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println(response.statusCode() + " " + response.body());
        if (response.statusCode() != 202) throw new IllegalStateException("Upload failed: " + response.statusCode());
    }

    private static String newId() { return UUID.randomUUID().toString(); }
    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name);
        return value;
    }
}
