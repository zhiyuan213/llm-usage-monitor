package com.metertrail.sdk;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Source-only SDK: record does not wait for network; flush and close are bounded. */
public final class MeterTrail implements AutoCloseable {
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final String endpoint, key;
  private final Map<String, String> agent;
  private final Consumer<JsonNode> onResult;
  private final Consumer<String> onError;
  private final ArrayDeque<JsonNode> queue = new ArrayDeque<>();
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "metertrail");
            t.setDaemon(true);
            return t;
          });
  private long bytes = 0;
  private volatile String paused;
  private volatile long retryAfterNanos;
  private volatile boolean closed;
  private String pending;
  private List<JsonNode> pendingEvents;

  public MeterTrail(
      String endpoint,
      String key,
      String serviceName,
      Consumer<JsonNode> onResult,
      Consumer<String> onError) {
    this.endpoint = endpoint.replaceAll("/$", "");
    this.key = Objects.requireNonNull(key);
    this.onResult = onResult;
    this.onError = onError;
    URI uri = URI.create(endpoint);
    if (!uri.getScheme().equals("https")
        && !Set.of("localhost", "127.0.0.1").contains(uri.getHost()))
      throw new IllegalArgumentException("HTTPS required outside localhost");
    agent =
        Map.of(
            "serviceName",
            serviceName,
            "environment",
            "production",
            "instanceId",
            UUID.randomUUID().toString(),
            "sdkName",
            "metertrail-java",
            "sdkVersion",
            "0.1.0");
    executor.scheduleWithFixedDelay(this::drainSafely, 5, 5, TimeUnit.SECONDS);
  }

  private void error(String message) {
    try {
      onError.accept(message);
    } catch (Exception ignored) {
    }
  }

  public boolean record(JsonNode input) {
    if (input == null || !input.isObject()) {
      error("INVALID_EVENT");
      return false;
    }
    ObjectNode event = input.deepCopy();
    if (!event.has("eventId")) event.put("eventId", UUID.randomUUID().toString());
    if (!event.has("revision")) event.put("revision", 1);
    if (!event.has("startedAt")) event.put("startedAt", Instant.now().toString());
    int size = event.toString().getBytes(StandardCharsets.UTF_8).length;
    boolean wake;
    synchronized (queue) {
      if (closed) {
        error("CLOSED");
        return false;
      }
      if (size > 8192) {
        error("EVENT_TOO_LARGE");
        return false;
      }
      if (queue.size() >= 10000 || bytes + size > 16 * 1024 * 1024) {
        error("BUFFER_OVERFLOW");
        return false;
      }
      queue.add(event);
      bytes += size;
      wake = queue.size() >= 100 || bytes >= 256 * 1024;
    }
    if (wake) executor.execute(this::drainSafely);
    return true;
  }

  private record Response(int status, JsonNode data) {}

  private Response request(String path, JsonNode body, long deadline) throws Exception {
    for (int attempt = 0; ; attempt++) {
      if (System.nanoTime() > deadline) throw new TimeoutException("FLUSH_TIMEOUT");
      HttpRequest.Builder b =
          HttpRequest.newBuilder(URI.create(endpoint + path))
              .header("Authorization", "Bearer " + key)
              .header("Content-Type", "application/json")
              .timeout(
                  Duration.ofMillis(
                      Math.max(
                          1,
                          Math.min(
                              10000,
                              TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))));
      if (body == null) b.GET();
      else b.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
      try {
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode data = json.readTree(r.body());
        if (r.statusCode() == 429 && !data.path("code").asText().equals("QUOTA_EXCEEDED")) {
          long seconds = Long.parseLong(r.headers().firstValue("Retry-After").orElse("0"));
          if (seconds > 0) {
            retryAfterNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            return new Response(r.statusCode(), data);
          }
        }
        if (attempt < 5
            && (r.statusCode() >= 500
                || (r.statusCode() == 429
                    && !data.path("code").asText().equals("QUOTA_EXCEEDED")))) {
          long retry = Long.parseLong(r.headers().firstValue("Retry-After").orElse("0")) * 1000;
          Thread.sleep(
              Math.min(
                  10000,
                  Math.max(retry, 250L << attempt) + ThreadLocalRandom.current().nextInt(250)));
          continue;
        }
        return new Response(r.statusCode(), data);
      } catch (java.io.IOException e) {
        if (attempt >= 5) throw e;
        Thread.sleep(Math.min(10000, (250L << attempt) + ThreadLocalRandom.current().nextInt(250)));
      }
    }
  }

  private void drainSafely() {
    try {
      drain(System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
    } catch (Exception e) {
      error(e instanceof InterruptedException ? "INTERRUPTED" : "NETWORK_ERROR: " + e.getMessage());
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    }
  }

  private void drain(long deadline) throws Exception {
    while (paused == null && System.nanoTime() < deadline && System.nanoTime() >= retryAfterNanos) {
      if (pending != null) {
        Response r = request("/v1/batches/" + pending, null, deadline);
        if (r.status() != 200) {
          if (r.status() == 401 || r.status() == 403) paused = "INVALID_API_KEY";
          error("BATCH_QUERY_FAILED");
          return;
        }
        if (r.data().path("status").asText().equals("pending")) {
          Thread.sleep(300);
          continue;
        }
        if (!r.data().path("status").asText().equals("completed")) {
          error("BATCH_FAILED: " + r.data().path("status").asText());
          pending = null;
          pendingEvents = null;
          return;
        }
        for (JsonNode e : r.data().path("errors")) {
          error(e.path("code").asText() + " index=" + e.path("index").asInt());
          if (e.path("code").asText().equals("QUOTA_EXCEEDED")) {
            paused = "QUOTA_EXCEEDED";
            JsonNode event = pendingEvents.get(e.path("index").asInt());
            int size = event.toString().getBytes(StandardCharsets.UTF_8).length;
            synchronized (queue) {
              if (queue.size() < 10000 && bytes + size <= 16 * 1024 * 1024) {
                queue.add(event);
                bytes += size;
              } else error("BUFFER_OVERFLOW");
            }
          }
        }
        try {
          onResult.accept(r.data());
        } catch (Exception ignored) {
        }
        pending = null;
        pendingEvents = null;
        continue;
      }
      List<JsonNode> events = new ArrayList<>();
      long size = 0;
      synchronized (queue) {
        for (JsonNode event : queue) {
          int n = event.toString().getBytes(StandardCharsets.UTF_8).length;
          if (events.size() >= 100 || (!events.isEmpty() && size + n > 256 * 1024)) break;
          events.add(event);
          size += n;
        }
      }
      if (events.isEmpty()) return;
      Response r =
          request(
              "/v1/events/batch",
              json.valueToTree(Map.of("schemaVersion", "1.0", "agent", agent, "events", events)),
              deadline);
      if (r.status() == 202) {
        pending = r.data().path("batchId").asText();
        pendingEvents = events;
      } else if (r.status() == 401
          || r.status() == 403
          || r.data().path("code").asText().equals("QUOTA_EXCEEDED")) {
        paused = r.data().path("code").asText("INVALID_API_KEY");
        error(paused);
        return;
      } else if (r.status() >= 500 || r.status() == 429) {
        error("RETRY_EXHAUSTED");
        return;
      } else if (r.status() == 413 && events.size() > 1) {
        for (JsonNode event : events) {
          Response one =
              request(
                  "/v1/events/batch",
                  json.valueToTree(
                      Map.of("schemaVersion", "1.0", "agent", agent, "events", List.of(event))),
                  deadline);
          if (one.status() != 202) {
            error(one.data().path("code").asText());
            return;
          }
          Response result;
          do {
            Thread.sleep(100);
            result = request("/v1/batches/" + one.data().path("batchId").asText(), null, deadline);
          } while (result.status() == 200
              && result.data().path("status").asText().equals("pending"));
          if (result.status() != 200) return;
          try {
            onResult.accept(result.data());
          } catch (Exception ignored) {
          }
        }
      } else error(r.data().path("code").asText("PERMANENT_ERROR"));
      synchronized (queue) {
        for (int i = 0; i < events.size(); i++) queue.removeFirst();
        bytes -= size;
      }
    }
  }

  public boolean flush(Duration timeout) {
    if (executor.isShutdown()) return false;
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      executor
          .submit(
              () -> {
                try {
                  drain(deadline);
                } catch (Exception e) {
                  error(e.getMessage());
                }
              })
          .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      synchronized (queue) {
        return queue.isEmpty() && pending == null;
      }
    } catch (Exception e) {
      error("FLUSH_TIMEOUT");
      return false;
    }
  }

  public String pausedReason() {
    return paused;
  }

  public void resume() {
    paused = null;
    executor.execute(this::drainSafely);
  }

  @Override
  public void close() {
    closed = true;
    flush(Duration.ofSeconds(10));
    executor.shutdownNow();
  }
}
