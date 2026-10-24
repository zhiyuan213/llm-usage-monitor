package com.metertrail;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class Store {
  final JdbcTemplate db;
  final JdbcTemplate read;
  final Secrets secrets;

  Store(JdbcTemplate db, Secrets secrets) {
    this(db, db, secrets);
  }

  @org.springframework.beans.factory.annotation.Autowired
  Store(
      @org.springframework.beans.factory.annotation.Qualifier("jdbcTemplate") JdbcTemplate db,
      @org.springframework.beans.factory.annotation.Qualifier("readJdbcTemplate") JdbcTemplate read,
      Secrets secrets) {
    this.db = db;
    this.read = read;
    this.secrets = secrets;
  }

  // Write-transaction queries must see their own uncommitted identity/ledger changes.
  JdbcTemplate queries() {
    return org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()
            && !org.springframework.transaction.support.TransactionSynchronizationManager
                .isCurrentTransactionReadOnly()
        ? db
        : read;
  }

  Map<String, Object> workspace(UUID user) {
    return queries().queryForList("SELECT * FROM workspaces WHERE owner_id=?", user).stream()
        .findFirst()
        .orElseThrow(() -> new ApiError(404, "WORKSPACE_NOT_FOUND"));
  }

  Map<String, Object> project(UUID user, UUID project) {
    return queries()
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
    return keyScope(bearer).project();
  }

  record KeyScope(UUID project, UUID workspace, String hash) {}

  KeyScope keyScope(String bearer) {
    if (bearer == null || !bearer.startsWith("Bearer lm_live_"))
      throw new ApiError(401, "INVALID_API_KEY");
    String hash = Support.hash(bearer.substring(7));
    return queries()
        .queryForList(
            "SELECT k.project_id,p.workspace_id FROM api_keys k JOIN projects p ON"
                + " p.id=k.project_id WHERE k.key_hash=? AND k.revoked_at IS NULL AND p.deleted_at"
                + " IS NULL",
            hash)
        .stream()
        .map(r -> new KeyScope((UUID) r.get("project_id"), (UUID) r.get("workspace_id"), hash))
        .findFirst()
        .orElseThrow(() -> new ApiError(401, "INVALID_API_KEY"));
  }
}
