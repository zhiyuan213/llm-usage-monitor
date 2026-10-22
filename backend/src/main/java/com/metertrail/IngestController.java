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

  IngestController(Store store, IngestService ingest, RateLimits limits) {
    this.store = store;
    this.ingest = ingest;
    this.limits = limits;
  }

  static byte[] bounded(InputStream in) throws IOException {
    byte[] b = in.readNBytes(1_048_577);
    if (b.length > 1_048_576) throw new ApiError(413, "PAYLOAD_TOO_LARGE");
    return b;
  }

  @PostMapping(value = "/v1/events/batch", produces = "application/json")
  ResponseEntity<?> batch(HttpServletRequest req) throws IOException {
    UUID project = store.key(req.getHeader("Authorization"));
    limits.check("ingest:" + project, 240000);
    Long queued =
        store.db.queryForObject(
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
    return ResponseEntity.ok(ingest.accept(project, b));
  }

  @GetMapping("/v1/batches/{id}")
  Map<String, Object> status(
      @PathVariable UUID id,
      @RequestHeader(value = "Authorization", required = false) String bearer) {
    UUID project = store.key(bearer);
    var rows =
        store.db.queryForList(
            "SELECT status,received,accepted,duplicates,rejected,errors FROM ingest_batches WHERE"
                + " id=? AND project_id=?",
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
    return store.db.queryForMap(
        "SELECT w.plan,w.used_units,w.period_end,w.auto_overage,w.overage_cap FROM workspaces w"
            + " JOIN projects p ON p.workspace_id=w.id WHERE p.id=?",
        project);
  }
}
