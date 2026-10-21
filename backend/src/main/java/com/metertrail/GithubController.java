package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
class GithubController {
  final Store store;
  final AuthController auth;
  final Challenges challenges;
  final StringRedisTemplate redis;
  final TransactionTemplate tx;
  final String client, secret, publicUrl;

  GithubController(
      Store store,
      AuthController auth,
      Challenges challenges,
      StringRedisTemplate redis,
      TransactionTemplate tx,
      @Value("${metertrail.github-client-id}") String client,
      @Value("${metertrail.github-client-secret}") String secret,
      @Value("${metertrail.public-url}") String publicUrl) {
    this.store = store;
    this.auth = auth;
    this.challenges = challenges;
    this.redis = redis;
    this.tx = tx;
    this.client = client;
    this.secret = secret;
    this.publicUrl = publicUrl;
  }

  @GetMapping("/api/auth/providers")
  Map<String, Boolean> providers() {
    return Map.of("github", !client.isBlank());
  }

  String begin(String user, HttpServletResponse response) {
    if (client.isBlank()) throw new ApiError(503, "GITHUB_NOT_CONFIGURED");
    String state = Support.token();
    redis.opsForValue().set("oauth:" + state, user, Duration.ofMinutes(5));
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from("mt_oauth", state)
            .httpOnly(true)
            .secure(auth.secure)
            .sameSite("Lax")
            .path("/api/auth/github")
            .maxAge(300)
            .build()
            .toString());
    return "https://github.com/login/oauth/authorize?client_id="
        + client
        + "&scope=read:user%20user:email&state="
        + state;
  }

  @GetMapping("/api/auth/github")
  void start(@RequestParam(defaultValue = "false") boolean rememberMe, HttpServletResponse res)
      throws java.io.IOException {
    res.sendRedirect(begin(rememberMe ? "login:remember" : "login", res));
  }

  @PostMapping("/api/account/github/link")
  Map<String, String> link(
      @RequestBody JsonNode b, HttpServletRequest req, HttpServletResponse res) {
    challenges.consume(
        SessionFilter.uid(req),
        "github.link",
        "github",
        b.path("challengeId").asText(),
        b.path("code").asText(),
        req);
    return Map.of("url", begin(SessionFilter.uid(req).toString(), res));
  }

  @PostMapping("/api/account/github/unlink")
  Map<String, String> unlink(@RequestBody JsonNode b, HttpServletRequest req) {
    UUID user = SessionFilter.uid(req);
    Support.require(SessionFilter.user(req).get("password_hash") != null, "LAST_LOGIN_METHOD");
    challenges.consume(
        user,
        "github.unlink",
        "github",
        b.path("challengeId").asText(),
        b.path("code").asText(),
        req);
    tx.executeWithoutResult(
        s -> {
          store.db.update("UPDATE users SET github_id=NULL WHERE id=?", user);
          store.audit(user, null, "github.unlink", "github", true, req);
        });
    return Map.of("status", "unlinked");
  }

  @GetMapping("/api/auth/github/callback")
  void callback(
      @RequestParam String code,
      @RequestParam String state,
      HttpServletRequest req,
      HttpServletResponse res)
      throws java.io.IOException {
    String cookie = "";
    if (req.getCookies() != null)
      for (Cookie c : req.getCookies()) if (c.getName().equals("mt_oauth")) cookie = c.getValue();
    if (!Support.equal(cookie, state)) throw new ApiError(400, "OAUTH_STATE_INVALID");
    String intent = redis.opsForValue().getAndDelete("oauth:" + state);
    if (intent == null) throw new ApiError(400, "OAUTH_STATE_INVALID");
    var http = RestClient.create();
    JsonNode token =
        http.post()
            .uri("https://github.com/login/oauth/access_token")
            .accept(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "client_id",
                    client,
                    "client_secret",
                    secret,
                    "code",
                    code,
                    "redirect_uri",
                    publicUrl + "/api/auth/github/callback"))
            .retrieve()
            .body(JsonNode.class);
    if (token == null || !token.has("access_token")) throw new ApiError(400, "OAUTH_FAILED");
    String bearer = "Bearer " + token.path("access_token").asText();
    JsonNode profile =
        http.get()
            .uri("https://api.github.com/user")
            .header("Authorization", bearer)
            .retrieve()
            .body(JsonNode.class);
    JsonNode emails =
        http.get()
            .uri("https://api.github.com/user/emails")
            .header("Authorization", bearer)
            .retrieve()
            .body(JsonNode.class);
    if (profile == null || !profile.has("id")) throw new ApiError(400, "OAUTH_FAILED");
    String github = profile.path("id").asText();
    String email = null;
    if (emails != null)
      for (JsonNode e : emails)
        if (e.path("verified").asBoolean() && (email == null || e.path("primary").asBoolean()))
          email = e.path("email").asText().toLowerCase(Locale.ROOT);
    final String verifiedEmail = email;
    UUID user =
        tx.execute(
            s -> {
              var existing =
                  store.db.queryForList("SELECT id FROM users WHERE github_id=?", github);
              if (!intent.equals("login") && !intent.equals("login:remember")) {
                UUID uid = Support.uuid(intent);
                if (!existing.isEmpty() && !uid.equals(existing.get(0).get("id")))
                  throw new ApiError(409, "GITHUB_ALREADY_LINKED");
                store.db.update("UPDATE users SET github_id=? WHERE id=?", github, uid);
                store.audit(uid, null, "github.link", "github", true, req);
                return uid;
              }
              if (!existing.isEmpty()) return (UUID) existing.get(0).get("id");
              if (verifiedEmail == null) throw new ApiError(403, "GITHUB_VERIFIED_EMAIL_REQUIRED");
              if (!store
                  .db
                  .queryForList("SELECT id FROM users WHERE email=?", verifiedEmail)
                  .isEmpty()) throw new ApiError(409, "LOGIN_AND_LINK_EXISTING_ACCOUNT");
              UUID uid = UUID.randomUUID();
              store.db.update(
                  "INSERT INTO users(id,email,verified,github_id) VALUES(?,?,true,?)",
                  uid,
                  verifiedEmail,
                  github);
              store.db.update(
                  "INSERT INTO workspaces(id,owner_id,name) VALUES(?,?,?)",
                  UUID.randomUUID(),
                  uid,
                  "My workspace");
              store.audit(uid, null, "github.register", "github", true, req);
              return uid;
            });
    auth.session(user, res, intent.equals("login:remember"));
    res.sendRedirect(publicUrl + "/app/overview");
  }
}
