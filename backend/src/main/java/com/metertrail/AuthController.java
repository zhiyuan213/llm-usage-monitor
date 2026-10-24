package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class AuthController {
  final Store store;
  final Challenges challenges;
  final BCryptPasswordEncoder passwords;
  final RateLimits rates;
  final boolean secure;

  AuthController(
      Store store,
      Challenges challenges,
      BCryptPasswordEncoder passwords,
      RateLimits rates,
      @Value("${metertrail.secure-cookies}") boolean secure) {
    this.store = store;
    this.challenges = challenges;
    this.passwords = passwords;
    this.rates = rates;
    this.secure = secure;
  }

  static String email(JsonNode b) {
    String e = Support.text(b, "email", 254).trim().toLowerCase(Locale.ROOT);
    Support.require(e.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"), "INVALID_EMAIL");
    return e;
  }

  static String password(JsonNode b) {
    String p = Support.text(b, "password", 72);
    Support.require(p.length() >= 12, "PASSWORD_TOO_SHORT");
    return p;
  }

  @PostMapping("/api/auth/register")
  @Transactional
  Map<String, Object> register(@RequestBody JsonNode b, HttpServletRequest req) {
    rates.check("register:" + req.getRemoteAddr(), 10);
    String e = email(b);
    String p = password(b);
    if (!store.queries().queryForList("SELECT id FROM users WHERE email=?", e).isEmpty())
      throw new ApiError(409, "EMAIL_UNAVAILABLE");
    UUID user = UUID.randomUUID(), workspace = UUID.randomUUID();
    store.db.update(
        "INSERT INTO users(id,email,password_hash) VALUES(?,?,?)", user, e, passwords.encode(p));
    store.db.update(
        "INSERT INTO workspaces(id,owner_id,name) VALUES(?,?,?)", workspace, user, "My workspace");
    UUID challenge = challenges.issue(user, "verify-email", e, req);
    store.audit(user, workspace, "account.register", user.toString(), true, req);
    return Map.of("challengeId", challenge, "email", e);
  }

  @PostMapping("/api/auth/login")
  Map<String, Object> login(
      @RequestBody JsonNode b, HttpServletRequest req, HttpServletResponse res) {
    String e = email(b);
    rates.check("login-ip:" + req.getRemoteAddr(), 30);
    rates.check("login:" + Support.hash(e), 10);
    var rows = store.queries().queryForList("SELECT * FROM users WHERE email=?", e);
    String dummy = "$2a$12$Q7w1cT3gwZILMJxQjggUyeYCbxA5FpkpwpZV3OQYiFvN55fjQg11e";
    String hash =
        rows.isEmpty() || rows.get(0).get("password_hash") == null
            ? dummy
            : (String) rows.get(0).get("password_hash");
    boolean match = passwords.matches(b.path("password").asText(), hash);
    if (rows.isEmpty() || !match) {
      store.audit(null, null, "account.login", Support.hash(e), false, req);
      throw new ApiError(401, "INVALID_CREDENTIALS");
    }
    if (!(boolean) rows.get(0).get("verified")) throw new ApiError(403, "EMAIL_NOT_VERIFIED");
    store.audit((UUID) rows.get(0).get("id"), null, "account.login", "session", true, req);
    return session((UUID) rows.get(0).get("id"), res, b.path("rememberMe").asBoolean(false));
  }

  Map<String, Object> session(UUID user, HttpServletResponse res) {
    return session(user, res, false);
  }

  Map<String, Object> session(UUID user, HttpServletResponse res, boolean rememberMe) {
    long seconds = rememberMe ? 30L * 86400 : 43200;
    String token = Support.token(), csrf = Support.token();
    store.db.update(
        "INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+(? * interval"
            + " '1 second'))",
        Support.hash(token),
        user,
        csrf,
        seconds);
    res.addHeader(
        "Set-Cookie",
        ResponseCookie.from("mt_session", token)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/")
            .maxAge(seconds)
            .build()
            .toString());
    return Map.of("csrf", csrf);
  }

  @PostMapping("/api/auth/challenge")
  Map<String, Object> publicChallenge(@RequestBody JsonNode b, HttpServletRequest req) {
    rates.check("challenge:" + req.getRemoteAddr(), 10);
    String e = email(b), action = b.path("action").asText();
    Support.require(Set.of("verify-email", "reset-password").contains(action), "INVALID_ACTION");
    var rows = store.queries().queryForList("SELECT id FROM users WHERE email=?", e);
    UUID id = UUID.randomUUID();
    if (!rows.isEmpty()) id = challenges.issue((UUID) rows.get(0).get("id"), action, e, req);
    return Map.of("challengeId", id, "message", "IF_ACCOUNT_EXISTS_EMAIL_SENT");
  }

  @PostMapping("/api/auth/verify")
  @Transactional
  Map<String, Object> verify(@RequestBody JsonNode b, HttpServletRequest req) {
    rates.check("verify:" + req.getRemoteAddr(), 30);
    String e = email(b);
    var rows = store.queries().queryForList("SELECT id FROM users WHERE email=?", e);
    if (rows.isEmpty()) throw new ApiError(400, "INVALID_VERIFICATION");
    UUID user = (UUID) rows.get(0).get("id");
    challenges.consume(
        user, "verify-email", e, b.path("challengeId").asText(), b.path("code").asText(), req);
    store.db.update("UPDATE users SET verified=true WHERE id=?", user);
    return Map.of("status", "verified");
  }

  @PostMapping("/api/auth/reset")
  @Transactional
  Map<String, Object> reset(@RequestBody JsonNode b, HttpServletRequest req) {
    rates.check("reset:" + req.getRemoteAddr(), 20);
    String e = email(b), p = password(b);
    var rows = store.queries().queryForList("SELECT id FROM users WHERE email=?", e);
    if (rows.isEmpty()) throw new ApiError(400, "INVALID_VERIFICATION");
    UUID user = (UUID) rows.get(0).get("id");
    challenges.consume(
        user, "reset-password", e, b.path("challengeId").asText(), b.path("code").asText(), req);
    store.db.update(
        "UPDATE users SET password_hash=?,verified=true WHERE id=?", passwords.encode(p), user);
    store.db.update("DELETE FROM sessions WHERE user_id=?", user);
    store.audit(user, null, "account.password-reset", user.toString(), true, req);
    return Map.of("status", "reset");
  }

  @GetMapping("/api/me")
  Map<String, Object> me(HttpServletRequest req) {
    var u = SessionFilter.user(req);
    return Map.of(
        "id",
        u.get("id"),
        "email",
        u.get("email"),
        "language",
        u.get("language"),
        "csrf",
        u.get("csrf"),
        "expiresAt",
        ((java.sql.Timestamp) u.get("session_expires_at")).toInstant().toString(),
        "githubConnected",
        u.get("github_id") != null,
        "workspace",
        store.workspace(SessionFilter.uid(req)));
  }

  @PostMapping("/api/logout")
  Map<String, String> logout(HttpServletRequest req, HttpServletResponse res) {
    if (req.getCookies() != null)
      for (Cookie c : req.getCookies())
        if (c.getName().equals("mt_session"))
          store.db.update("DELETE FROM sessions WHERE token_hash=?", Support.hash(c.getValue()));
    res.addHeader(
        "Set-Cookie",
        ResponseCookie.from("mt_session", "")
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/")
            .maxAge(0)
            .build()
            .toString());
    return Map.of("status", "ok");
  }

  @PostMapping("/api/preferences")
  Map<String, String> language(@RequestBody JsonNode b, HttpServletRequest req) {
    String lang = b.path("language").asText();
    Support.require(Set.of("en", "zh-CN").contains(lang), "INVALID_LANGUAGE");
    store.db.update("UPDATE users SET language=? WHERE id=?", lang, SessionFilter.uid(req));
    return Map.of("status", "ok");
  }
}
