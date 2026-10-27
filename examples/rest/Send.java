import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

class Send {
  public static void main(String[] args) throws Exception {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    String startedAt = Instant.now().toString();
    // Keep this payload unchanged when retrying the same upload.
    String batch = """
        {"events":[
          {"eventId":"%s","objectId":"%s","type":"trace","traceId":"%s","startedAt":"%s",
           "data":{"name":"Example request","status":"success","durationMs":120,
                   "userId":"demo-user","customerId":"demo-customer","feature":"document-analysis"}},
          {"eventId":"%s","objectId":"%s","type":"observation","traceId":"%s","spanId":"%s","startedAt":"%s",
           "data":{"name":"Model response","observationType":"generation","status":"success",
                   "durationMs":100,"provider":"example","model":"example-model",
                   "usage":{"inputTokens":1500,"outputTokens":400,"cachedInputTokens":500}}}
        ]}
        """.formatted(UUID.randomUUID(), UUID.randomUUID(), traceId, startedAt,
            UUID.randomUUID(), UUID.randomUUID(), traceId,
            UUID.randomUUID().toString().replace("-", "").substring(0,16), startedAt);
    var req = HttpRequest.newBuilder(
            URI.create(System.getenv("METERTRAIL_ENDPOINT") + "/v1/events/batch"))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer " + System.getenv("METERTRAIL_API_KEY"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(batch)).build();
    var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        .send(req, HttpResponse.BodyHandlers.ofString());
    System.out.println(response.statusCode() + " " + response.body());
    if (response.statusCode() != 200) System.exit(1);
  }
}
