package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class WorkspaceController {
  final Store store;

  WorkspaceController(Store store) {
    this.store = store;
  }

  @GetMapping("/api/workspace")
  Map<String, Object> overview(HttpServletRequest req) {
    UUID user = SessionFilter.uid(req);
    return Map.of(
        "workspace",
        store.workspace(user),
        "projects",
        store.queries().queryForList(
            "SELECT p.id,p.name,p.environment,p.created_at,count(o.object_id) AS"
                + " objects,count(o.object_id) FILTER(WHERE o.type='trace') AS traces,sum(o.cost)"
                + " AS cost,count(o.object_id) FILTER(WHERE o.data->>'observationType'='generation'"
                + " AND o.cost IS NULL) AS unknown_costs FROM projects p JOIN workspaces w ON"
                + " w.id=p.workspace_id LEFT JOIN objects o ON o.project_id=p.id AND"
                + " o.started_at>now()-make_interval(days=>CASE w.plan WHEN 'Pro' THEN 1096 WHEN"
                + " 'Core' THEN 90 ELSE 30 END) WHERE w.owner_id=? AND p.deleted_at IS NULL GROUP"
                + " BY p.id ORDER BY p.created_at",
            user));
  }

  @PostMapping("/api/projects/{project}/settings")
  @Transactional
  Map<String, String> update(
      @PathVariable UUID project, @RequestBody JsonNode body, HttpServletRequest req) {
    UUID user = SessionFilter.uid(req);
    var p = store.project(user, project);
    store.db.update(
        "UPDATE projects SET name=?,environment=? WHERE id=?",
        Support.text(body, "name", 128),
        Support.text(body, "environment", 128),
        project);
    store.audit(
        user, (UUID) p.get("workspace_id"), "project.settings", project.toString(), true, req);
    return Map.of("status", "updated");
  }
}
