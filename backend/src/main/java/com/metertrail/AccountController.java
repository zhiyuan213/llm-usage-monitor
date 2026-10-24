package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class AccountController {
  final Store store;
  final Challenges challenges;
  final RateLimits rates;

  AccountController(Store store, Challenges challenges, RateLimits rates) {
    this.store = store;
    this.challenges = challenges;
    this.rates = rates;
  }

  @PostMapping("/api/account/email/challenge")
  Map<String, Object> emailChallenge(@RequestBody JsonNode b, HttpServletRequest req) {
    String email = AuthController.email(b), action = b.path("action").asText();
    Support.require(Set.of("email.old", "email.new").contains(action), "INVALID_ACTION");
    rates.check("email-change:" + req.getRemoteAddr(), 10);
    if (!store.queries().queryForList("SELECT id FROM users WHERE email=?", email).isEmpty())
      throw new ApiError(409, "EMAIL_UNAVAILABLE");
    return Map.of("challengeId", challenges.issue(SessionFilter.uid(req), action, email, req));
  }

  @PostMapping("/api/account/email")
  @Transactional
  Map<String, String> email(@RequestBody JsonNode b, HttpServletRequest req) {
    UUID user = SessionFilter.uid(req);
    String email = AuthController.email(b);
    if (!store.queries().queryForList("SELECT id FROM users WHERE email=?", email).isEmpty())
      throw new ApiError(409, "EMAIL_UNAVAILABLE");
    challenges.consume(
        user,
        "email.old",
        email,
        b.path("oldChallengeId").asText(),
        b.path("oldCode").asText(),
        req);
    challenges.consume(
        user,
        "email.new",
        email,
        b.path("newChallengeId").asText(),
        b.path("newCode").asText(),
        req);
    store.db.update("UPDATE users SET email=? WHERE id=?", email, user);
    store.db.update("DELETE FROM sessions WHERE user_id=?", user);
    store.audit(user, null, "account.email-change", user.toString(), true, req);
    store.mail(
        (String) SessionFilter.user(req).get("email"),
        "MeterTrail security notice",
        "Your email address was changed. / 你的邮箱地址已变更。");
    return Map.of("status", "sign_in_required");
  }

  @PostMapping("/api/account/delete")
  @Transactional
  Map<String, String> delete(@RequestBody JsonNode b, HttpServletRequest req) {
    UUID user = SessionFilter.uid(req);
    Support.require(b.path("confirm").asText().equals("DELETE"), "CONFIRMATION_REQUIRED");
    var w = store.workspace(user);
    Support.require(
        w.get("subscription_id") == null
            || Set.of("canceled", "paused").contains(w.get("subscription_status")),
        "CANCEL_SUBSCRIPTION_FIRST");
    challenges.consume(
        user,
        "account.delete",
        user.toString(),
        b.path("challengeId").asText(),
        b.path("code").asText(),
        req);
    store.db.update("UPDATE projects SET deleted_at=now() WHERE workspace_id=?", w.get("id"));
    store.db.update(
        "UPDATE api_keys SET revoked_at=now() WHERE project_id IN (SELECT id FROM projects WHERE"
            + " workspace_id=?)",
        w.get("id"));
    store.db.update("DELETE FROM sessions WHERE user_id=?", user);
    store.db.update(
        "UPDATE users SET deleted_at=now(),email=?,password_hash=NULL,github_id=NULL WHERE id=?",
        "deleted-" + user + "@invalid.local",
        user);
    store.audit(user, (UUID) w.get("id"), "account.delete", user.toString(), true, req);
    return Map.of("status", "deletion_pending");
  }
}
