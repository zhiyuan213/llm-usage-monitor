package com.metertrail;

import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
class IngestController {
  final Store store;
  final IngestService ingest;
  final RateLimits limits;
  final IngestAdmission admission;
  final IngestPipeline pipeline;

  IngestController(
      Store store,
      IngestService ingest,
      RateLimits limits,
      IngestAdmission admission,
      IngestPipeline pipeline) {
    this.store = store;
    this.ingest = ingest;
    this.limits = limits;
    this.admission = admission;
    this.pipeline = pipeline;
  }

  static byte[] bounded(InputStream in) throws IOException {
    byte[] b = in.readNBytes(1_048_577);
    if (b.length > 1_048_576) throw new ApiError(413, "PAYLOAD_TOO_LARGE");
    return b;
  }

  @PostMapping(value = "/v1/events/batch", produces = "application/json")
  Object batch(HttpServletRequest req) throws IOException {
    if (pipeline.enabled()) return queuedBatch(req);
    try (var lease = admission.enter()) {
      return acceptBatch(req);
    }
  }

  private ResponseEntity<?> acceptBatch(HttpServletRequest req) throws IOException {
    try {
      UUID project = store.key(req.getHeader("Authorization"));
      return ResponseEntity.ok(ingest.accept(project, readBatch(req, project)));
    } catch (ApiError failure) {
      if (failure.status == 503) admission.unavailable(failure.code);
      throw failure;
    }
  }

  private Object queuedBatch(HttpServletRequest req) throws IOException {
    try {
      var scope = store.keyScope(req.getHeader("Authorization"));
      var ticket = pipeline.submit(scope, readBatch(req, scope.project()));
      var response =
          new org.springframework.web.context.request.async.DeferredResult<ResponseEntity<?>>(
              (long) pipeline.options.waitMillis());
      ticket.result.whenComplete(
          (result, failure) -> {
            if (failure != null)
              response.setErrorResult(new ApiError(503, "INGEST_QUEUE_UNAVAILABLE"));
            else if (result.status() == 200) response.setResult(ResponseEntity.ok(result.body()));
            else {
              String code = (String) result.body().get("code");
              if (result.status() == 503) admission.unavailable(code);
              response.setErrorResult(new ApiError(result.status(), code));
            }
          });
      response.onTimeout(ticket::uncertain);
      response.onError(failure -> ticket.uncertain());
      response.onCompletion(
          () -> {
            if (!ticket.result.isDone()) ticket.uncertain();
          });
      return response;
    } catch (ApiError failure) {
      if (failure.status == 503) admission.unavailable(failure.code);
      throw failure;
    } catch (org.springframework.dao.DataAccessResourceFailureException
        | org.springframework.dao.TransientDataAccessException failure) {
      admission.unavailable("INGEST_QUEUE_UNAVAILABLE");
      throw new ApiError(503, "INGEST_QUEUE_UNAVAILABLE");
    }
  }

  private com.fasterxml.jackson.databind.JsonNode readBatch(HttpServletRequest req, UUID project)
      throws IOException {
    limits.check("ingest:" + project, 240000);
    Long queued =
        store
            .queries()
            .queryForObject(
                "SELECT coalesce(sum(accepted),0) FROM ingest_batches WHERE status IN"
                    + " ('pending','quarantined')",
                Long.class);
    if (queued > 50000) throw new ApiError(503, "INGEST_BACKLOG_FULL");
    if (req.getContentType() == null
        || !req.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json"))
      throw new ApiError(415, "INVALID_CONTENT_TYPE");
    byte[] bytes = bounded(req.getInputStream());
    String encoding = req.getHeader("Content-Encoding");
    if (encoding != null && !encoding.equals("identity")) {
      if (!encoding.equals("gzip")) throw new ApiError(415, "UNSUPPORTED_ENCODING");
      try {
        bytes = bounded(new GZIPInputStream(new ByteArrayInputStream(bytes)));
      } catch (java.util.zip.ZipException e) {
        throw new ApiError(400, "INVALID_GZIP");
      }
    }
    var b = Support.parse(new String(bytes, StandardCharsets.UTF_8));
    Protocol.envelope(b);
    return b;
  }

  @GetMapping("/v1/batches/{id}")
  Map<String, Object> status(
      @PathVariable UUID id,
      @RequestHeader(value = "Authorization", required = false) String bearer) {
    UUID project = store.key(bearer);
    var rows =
        store
            .queries()
            .queryForList(
                "SELECT status,received,accepted,duplicates,rejected,errors FROM ingest_batches"
                    + " WHERE id=? AND project_id=?",
                id,
                project);
    if (rows.isEmpty()) throw new ApiError(404, "BATCH_NOT_FOUND");
    var row = rows.get(0);
    row.put("errors", Support.parse(row.get("errors").toString()));
    return row;
  }

  @GetMapping("/v1/quota")
  Map<String, Object> quota(
      @RequestHeader(value = "Authorization", required = false) String bearer) {
    UUID project = store.key(bearer);
    return store
        .queries()
        .queryForMap(
            "SELECT"
                + " w.plan,w.used_units,w.period_end,w.auto_overage,w.overage_cap,w.addon_units,w.addon_consumed,w.overage_units,"
                + " CASE WHEN w.plan<>'Free' AND (w.subscription_status<>'active' OR"
                + " w.period_end<=now()) THEN 0 ELSE greatest(0,(CASE WHEN w.plan='Free' THEN 50000"
                + " ELSE 100000"
                + " END)-(w.used_units-w.overage_units-w.addon_consumed))+coalesce((SELECT"
                + " sum(c.units-c.consumed_units) FROM usage_charges c WHERE c.workspace_id=w.id"
                + " AND c.period_start=w.period_start AND c.kind='addon' AND c.credited AND NOT"
                + " c.revoked AND c.status='paid'),0) END AS available_units FROM workspaces w JOIN"
                + " projects p ON p.workspace_id=w.id WHERE p.id=?",
            project);
  }
}
