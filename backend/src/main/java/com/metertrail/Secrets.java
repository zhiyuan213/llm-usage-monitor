package com.metertrail;

import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class Secrets {
  final String secret;

  Secrets(@Value("${metertrail.secret}") String secret) {
    if (secret.length() < 32)
      throw new IllegalArgumentException("APP_SECRET must have at least 32 characters");
    this.secret = secret;
  }

  String seal(String value) {
    try {
      byte[] iv = new byte[12];
      Support.RANDOM.nextBytes(iv);
      Cipher c = cipher(Cipher.ENCRYPT_MODE, iv);
      return Base64.getEncoder().encodeToString(iv)
          + ":"
          + Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String open(String value) {
    try {
      String[] a = value.split(":", 2);
      return new String(
          cipher(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(a[0]))
              .doFinal(Base64.getDecoder().decode(a[1])),
          StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Cipher cipher(int mode, byte[] iv) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        mode,
        new SecretKeySpec(HexFormat.of().parseHex(Support.hash(secret)), "AES"),
        new GCMParameterSpec(128, iv));
    return c;
  }
}
