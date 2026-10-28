package com.metertrail.example;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metertrail.example.model.BatchReceipt;
import com.metertrail.example.model.BatchRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Reuse one client in your application. No model calls or automatic retries. */
public final class MeterTrailClient {
    private final URI batchEndpoint;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MeterTrailClient(String endpoint, String apiKey) {
        if (endpoint == null || endpoint.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("endpoint and apiKey are required");
        }
        this.batchEndpoint = URI.create(endpoint.replaceAll("/+$", "") + "/v1/events/batch");
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public BatchReceipt sendBatch(BatchRequest batch) throws IOException, InterruptedException {
        byte[] payload = objectMapper.writeValueAsBytes(batch);
        HttpRequest request = HttpRequest.newBuilder(batchEndpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 202) {
            throw new IOException("Upload failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return objectMapper.readValue(response.body(), BatchReceipt.class);
    }
}
