package com.metertrail;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

final class Support {
  static final SecureRandom RANDOM = new SecureRandom();

  static String token() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String hmac(String key, String value) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(m.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static boolean equal(String a, String b) {
    return a != null
        && b != null
        && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  static String canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode o = JsonNodeFactory.instance.objectNode();
      TreeSet<String> keys = new TreeSet<>();
      node.fieldNames().forEachRemaining(keys::add);
      keys.forEach(k -> o.set(k, parse(canonical(node.get(k)))));
      return o.toString();
    }
    if (node.isArray()) {
      ArrayNode a = JsonNodeFactory.instance.arrayNode();
      node.forEach(n -> a.add(parse(canonical(n))));
      return a.toString();
    }
    return node.toString();
  }

  static JsonNode parse(String s) {
    try {
      return new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(s);
    } catch (Exception e) {
      throw new ApiError(400, "INVALID_JSON");
    }
  }

  static UUID uuid(String s) {
    try {
      return UUID.fromString(s);
    } catch (Exception e) {
      throw new ApiError(400, "INVALID_ID");
    }
  }

  static String text(JsonNode n, String key, int max) {
    JsonNode v = n.get(key);
    if (v == null
        || !v.isTextual()
        || v.asText().isBlank()
        || v.asText().getBytes(StandardCharsets.UTF_8).length > max)
      throw new ApiError(400, "INVALID_" + key.toUpperCase(Locale.ROOT));
    return v.asText();
  }

  static void require(boolean ok, String code) {
    if (!ok) throw new ApiError(400, code);
  }
}

class ApiError extends RuntimeException {
  final int status;
  final String code;

  ApiError(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

@RestControllerAdvice
class Errors {
  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> known(ApiError e) {
    var b = ResponseEntity.status(e.status);
    if (e.status == 429 && !e.code.equals("QUOTA_EXCEEDED")) b.header("Retry-After", "60");
    return b.body(Map.of("code", e.code));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> malformed(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception e) {
    return ResponseEntity.status(409).body(Map.of("code", "CONFLICT"));
  }
}
