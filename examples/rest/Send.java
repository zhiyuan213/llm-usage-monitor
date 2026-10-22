import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

class Send {
  public static void main(String[] args) throws Exception {
    var req =
        HttpRequest.newBuilder(
                URI.create(System.getenv("METERTRAIL_ENDPOINT") + "/v1/events/batch"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + System.getenv("METERTRAIL_API_KEY"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofFile(Path.of("examples/batch.json")))
            .build();
    var r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    System.out.println(r.statusCode() + " " + r.body());
    if (r.statusCode() != 200) System.exit(1);
  }
}
