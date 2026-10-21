package com.metertrail;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class Store {
  final JdbcTemplate db;
  final Secrets secrets;

  Store(JdbcTemplate db, Secrets secrets) {
    this.db = db;
    this.secrets = secrets;
  }

  Map<String, Object> workspace(UUID user) {
    return db.queryForList("SELECT * FROM workspaces WHERE owner_id=?", user).stream()
        .findFirst()
        .orElseThrow(() -> new ApiError(404, "WORKSPACE_NOT_FOUND"));
  }

  Map<String, Object> project(UUID user, UUID project) {
    return db
        .queryForList(
            "SELECT p.* FROM projects p JOIN workspaces w ON w.id=p.workspace_id WHERE p.id=? AND"
                + " w.owner_id=? AND p.deleted_at IS NULL",
            project,
            user)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiError(404, "PROJECT_NOT_FOUND"));
  }

  void audit(
      UUID user,
      UUID workspace,
      String action,
      String target,
      boolean success,
      HttpServletRequest req) {
    db.update(
        "INSERT INTO"
            + " audit_logs(user_id,workspace_id,action,target,success,ip,user_agent,request_id)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        user,
        workspace,
        action,
        target,
        success,
        req == null ? null : req.getRemoteAddr(),
        req == null ? null : req.getHeader("User-Agent"),
        req == null ? null : req.getAttribute("requestId"));
  }

  void mail(String recipient, String subject, String body) {
    db.update(
        "INSERT INTO outbox(recipient,subject,body) VALUES(?,?,?)",
        recipient,
        subject,
        secrets.seal(body));
  }

  UUID key(String bearer) {
    if (bearer == null || !bearer.startsWith("Bearer lm_live_"))
      throw new ApiError(401, "INVALID_API_KEY");
    return db
        .queryForList(
            "SELECT k.project_id FROM api_keys k JOIN projects p ON p.id=k.project_id WHERE"
                + " k.key_hash=? AND k.revoked_at IS NULL AND p.deleted_at IS NULL",
            Support.hash(bearer.substring(7)))
        .stream()
        .map(r -> (UUID) r.get("project_id"))
        .findFirst()
        .orElseThrow(() -> new ApiError(401, "INVALID_API_KEY"));
  }
}
