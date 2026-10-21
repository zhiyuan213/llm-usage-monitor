package com.metertrail;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class Security {
  @Bean
  SecurityFilterChain chain(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable())
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .sessionManagement(
            s ->
                s.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .build();
  }

  @Bean
  BCryptPasswordEncoder passwords() {
    return new BCryptPasswordEncoder(12);
  }
}

@Component
class SessionFilter extends OncePerRequestFilter {
  final JdbcTemplate db;

  SessionFilter(JdbcTemplate db) {
    this.db = db;
  }

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    req.setAttribute("requestId", id);
    res.setHeader("X-Request-ID", id);
    res.setHeader("X-Content-Type-Options", "nosniff");
    String path = req.getRequestURI();
    if (path.startsWith("/api/") && !path.startsWith("/api/auth/")) {
      String token = null;
      if (req.getCookies() != null)
        for (Cookie c : req.getCookies())
          if (c.getName().equals("mt_session")) token = c.getValue();
      var rows =
          db.queryForList(
              "SELECT u.*,s.csrf,s.expires_at AS session_expires_at FROM sessions s JOIN users u ON"
                  + " u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>now() AND u.deleted_at"
                  + " IS NULL",
              Support.hash(token == null ? "" : token));
      if (rows.isEmpty()) {
        error(res, 401, "UNAUTHENTICATED");
        return;
      }
      if (!Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())
          && !Support.equal((String) rows.get(0).get("csrf"), req.getHeader("X-CSRF-Token"))) {
        error(res, 403, "CSRF_INVALID");
        return;
      }
      req.setAttribute("user", rows.get(0));
    }
    chain.doFilter(req, res);
  }

  static void error(HttpServletResponse r, int status, String code) throws IOException {
    r.setStatus(status);
    r.setContentType("application/json");
    r.getWriter().write("{\"code\":\"" + code + "\"}");
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> user(HttpServletRequest req) {
    return (Map<String, Object>) req.getAttribute("user");
  }

  static UUID uid(HttpServletRequest req) {
    return (UUID) user(req).get("id");
  }
}
