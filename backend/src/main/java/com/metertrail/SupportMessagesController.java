package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Account-scoped support conversations. No public API can grant platform roles. */
@RestController
class SupportMessagesController {
  final Store store;
  final RateLimits limits;

  SupportMessagesController(Store store, RateLimits limits) {
    this.store = store;
    this.limits = limits;
  }

  boolean admin(HttpServletRequest req) {
    return req.getRequestURI().startsWith("/api/admin/");
  }

  void authorize(HttpServletRequest req) {
    if (admin(req)
        && !Boolean.TRUE.equals(
            store
                .queries()
                .queryForObject(
                    "SELECT is_admin AND verified AND deleted_at IS NULL FROM users WHERE id=?",
                    Boolean.class,
                    SessionFilter.uid(req)))) throw new ApiError(403, "ADMIN_REQUIRED");
  }

  Map<String, Object> thread(UUID id, HttpServletRequest req, boolean lock) {
    authorize(req);
    var rows =
        store
            .queries()
            .queryForList(
                "SELECT t.*,u.email AS user_email FROM support_threads t JOIN users u ON"
                    + " u.id=t.user_id WHERE t.id=?"
                    + (admin(req) ? "" : " AND t.user_id=?")
                    + (lock ? " FOR UPDATE OF t" : ""),
                admin(req) ? new Object[] {id} : new Object[] {id, SessionFilter.uid(req)});
    if (rows.isEmpty()) throw new ApiError(404, "MESSAGE_NOT_FOUND");
    return rows.get(0);
  }

  @GetMapping({"/api/messages", "/api/admin/messages"})
  @Transactional(
      transactionManager = "readTransactionManager",
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  Map<String, Object> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "5") int size,
      HttpServletRequest req) {
    authorize(req);
    Support.require(
        page >= 0 && page <= 100000 && Set.of(5, 10, 25, 50, 100).contains(size), "INVALID_PAGE");
    String where = admin(req) ? "" : " WHERE t.user_id=?";
    Object[] scope = admin(req) ? new Object[] {} : new Object[] {SessionFilter.uid(req)};
    Long total =
        store
            .queries()
            .queryForObject("SELECT count(*) FROM support_threads t" + where, Long.class, scope);
    var args = new ArrayList<Object>(Arrays.asList(scope));
    args.add(size);
    args.add((long) page * size);
    var items =
        store
            .queries()
            .queryForList(
                "SELECT"
                    + " t.id,t.title,t.reply_count,t.last_sender_kind,t.created_at,t.updated_at,u.email"
                    + " AS user_email FROM support_threads t JOIN users u ON u.id=t.user_id"
                    + where
                    + " ORDER BY t.updated_at DESC,t.id DESC LIMIT ? OFFSET ?",
                args.toArray());
    return Map.of("items", items, "total", total, "page", page, "size", size);
  }

  @GetMapping({"/api/messages/{id}", "/api/admin/messages/{id}"})
  @Transactional(
      transactionManager = "readTransactionManager",
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  Map<String, Object> detail(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "2147483647") int before,
      HttpServletRequest req) {
    Support.require(before > 0, "INVALID_PAGE");
    var root = thread(id, req, false);
    var replies =
        store
            .queries()
            .queryForList(
                "SELECT id,sender_kind,body,sequence,created_at FROM support_replies WHERE"
                    + " thread_id=? AND sequence<? ORDER BY sequence DESC LIMIT 50",
                id,
                before);
    Collections.reverse(replies);
    return Map.of(
        "thread",
        root,
        "replies",
        replies,
        "hasMore",
        !replies.isEmpty() && ((Number) replies.get(0).get("sequence")).intValue() > 1);
  }

  @PostMapping("/api/messages")
  @Transactional
  Map<String, Object> create(@RequestBody JsonNode body, HttpServletRequest req) {
    UUID user = SessionFilter.uid(req), client = Support.uuid(body.path("clientId").asText());
    String title = Support.text(body, "title", 240).strip(),
        text = Support.text(body, "body", 8000).strip();
    limits.check("support-create:" + user, 5);
    UUID id = UUID.randomUUID();
    // The user-row lock bounds per-account storage under concurrent requests.
    store.db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, user);
    var previous =
        store.db.queryForList(
            "SELECT * FROM support_threads WHERE user_id=? AND client_id=?", user, client);
    if (!previous.isEmpty()) return sameThread(previous.get(0), title, text);
    Long count =
        store.db.queryForObject(
            "SELECT count(*) FROM support_threads WHERE user_id=?", Long.class, user);
    if (count >= 1000) throw new ApiError(409, "MESSAGE_THREAD_LIMIT");
    store.db.update(
        "INSERT INTO support_threads(id,user_id,client_id,title,body) VALUES(?,?,?,?,?)",
        id,
        user,
        client,
        title,
        text);
    store.audit(user, null, "support.create", id.toString(), true, req);
    return Map.of("id", id);
  }

  Map<String, Object> sameThread(Map<String, Object> old, String title, String body) {
    if (!old.get("title").equals(title) || !old.get("body").equals(body))
      throw new ApiError(409, "MESSAGE_RETRY_CONFLICT");
    return Map.of("id", old.get("id"));
  }

  @PostMapping({"/api/messages/{id}/replies", "/api/admin/messages/{id}/replies"})
  @Transactional
  Map<String, Object> reply(
      @PathVariable UUID id, @RequestBody JsonNode body, HttpServletRequest req) {
    var root = thread(id, req, true);
    UUID user = SessionFilter.uid(req), client = Support.uuid(body.path("clientId").asText());
    String text = Support.text(body, "body", 8000).strip(), kind = admin(req) ? "admin" : "user";
    limits.check("support-reply:" + user, admin(req) ? 60 : 20);
    var old =
        store.db.queryForList(
            "SELECT id,thread_id,body,sender_kind FROM support_replies WHERE author_id=? AND"
                + " client_id=?",
            user,
            client);
    if (!old.isEmpty()) {
      var r = old.get(0);
      if (!r.get("thread_id").equals(id)
          || !r.get("body").equals(text)
          || !r.get("sender_kind").equals(kind)) throw new ApiError(409, "MESSAGE_RETRY_CONFLICT");
      return Map.of("id", r.get("id"));
    }
    int next = ((Number) root.get("reply_count")).intValue() + 1;
    if (next > 200) throw new ApiError(409, "MESSAGE_REPLY_LIMIT");
    UUID reply = UUID.randomUUID();
    int inserted =
        store.db.update(
            "INSERT INTO"
                + " support_replies(id,thread_id,author_id,client_id,sender_kind,body,sequence)"
                + " VALUES(?,?,?,?,?,?,?) ON CONFLICT(author_id,client_id) DO NOTHING",
            reply,
            id,
            user,
            client,
            kind,
            text,
            next);
    if (inserted == 0) {
      var r =
          store.db.queryForMap(
              "SELECT id,thread_id,body,sender_kind FROM support_replies WHERE author_id=? AND"
                  + " client_id=?",
              user,
              client);
      if (!r.get("thread_id").equals(id)
          || !r.get("body").equals(text)
          || !r.get("sender_kind").equals(kind)) throw new ApiError(409, "MESSAGE_RETRY_CONFLICT");
      return Map.of("id", r.get("id"));
    }
    store.db.update(
        "UPDATE support_threads SET reply_count=?,last_sender_kind=?,updated_at=now() WHERE id=?",
        next,
        kind,
        id);
    store.audit(
        user, null, admin(req) ? "support.admin-reply" : "support.reply", id.toString(), true, req);
    return Map.of("id", reply);
  }
}
