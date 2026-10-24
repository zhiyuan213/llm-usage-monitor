package com.metertrail;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Owner-scoped recovery of requests that never received an acceptance receipt. */
@RestController
class IngestQueueController {
  final Store store;
  final IngestPipeline pipeline;

  IngestQueueController(Store store, IngestPipeline pipeline) {
    this.store = store;
    this.pipeline = pipeline;
  }

  @GetMapping("/api/projects/{project}/ingest-queue")
  List<Map<String, Object>> failed(@PathVariable UUID project, HttpServletRequest req) {
    store.project(SessionFilter.uid(req), project);
    return store
        .queries()
        .queryForList(
            "SELECT id,state,attempts,last_error,received,created_at,completed_at FROM"
                + " ingest_accept_queue WHERE project_id=? AND state='quarantined' ORDER BY"
                + " created_at DESC LIMIT 100",
            project);
  }

  @PostMapping("/api/projects/{project}/ingest-queue/{id}/replay")
  @Transactional
  Map<String, Object> replay(
      @PathVariable UUID project, @PathVariable UUID id, HttpServletRequest req) {
    var p = store.project(SessionFilter.uid(req), project);
    int changed =
        store.db.update(
            "UPDATE ingest_accept_queue SET"
                + " state='queued',http_status=NULL,response=NULL,completed_at=NULL,"
                + " attempts=0,last_error=NULL,available_at=now() WHERE id=? AND project_id=? AND"
                + " state='quarantined'",
            id,
            project);
    if (changed != 1) throw new ApiError(409, "INGEST_NOT_QUARANTINED");
    store.audit(
        SessionFilter.uid(req),
        (UUID) p.get("workspace_id"),
        "ingest.queue.replay",
        id.toString(),
        true,
        req);
    if (pipeline.wakeup.availablePermits() == 0) pipeline.wakeup.release();
    return Map.of("id", id, "status", "queued");
  }

  @DeleteMapping("/api/projects/{project}/ingest-queue/{id}")
  @Transactional
  Map<String, Object> cancel(
      @PathVariable UUID project, @PathVariable UUID id, HttpServletRequest req) {
    var p = store.project(SessionFilter.uid(req), project);
    var rows =
        store.db.queryForList(
            "UPDATE ingest_accept_queue SET state='rejected',payload=NULL,http_status=503,"
                + " response='{\"code\":\"INGEST_CANCELED\"}'::jsonb,completed_at=now() WHERE id=?"
                + " AND project_id=? AND state='quarantined' RETURNING received,payload_bytes",
            id,
            project);
    if (rows.isEmpty()) throw new ApiError(409, "INGEST_NOT_QUARANTINED");
    store.db.update(
        "UPDATE ingest_queue_budget SET requests=requests-1,events=events-?,bytes=bytes-? WHERE"
            + " id=1",
        rows.get(0).get("received"),
        rows.get(0).get("payload_bytes"));
    store.audit(
        SessionFilter.uid(req),
        (UUID) p.get("workspace_id"),
        "ingest.queue.cancel",
        id.toString(),
        true,
        req);
    return Map.of("id", id, "status", "canceled");
  }
}
