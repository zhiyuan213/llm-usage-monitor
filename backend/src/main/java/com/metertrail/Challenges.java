package com.metertrail;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
class Challenges {
  final Store store;

  Challenges(Store store) {
    this.store = store;
  }

  @Transactional
  public UUID issue(UUID user, String action, String target, HttpServletRequest req) {
    var u = store.queries().queryForMap("SELECT * FROM users WHERE id=? FOR UPDATE", user);
    Integer recent =
        store.queries().queryForObject(
            "SELECT count(*) FROM verification_challenges WHERE user_id=? AND"
                + " created_at>now()-interval '60 seconds'",
            Integer.class,
            user);
    if (recent > 0) throw new ApiError(429, "RATE_LIMITED");
    store.db.update(
        "UPDATE verification_challenges SET consumed=true WHERE user_id=? AND action=?",
        user,
        action);
    UUID id = UUID.randomUUID();
    String code = String.format("%06d", Support.RANDOM.nextInt(1000000));
    store.db.update(
        "INSERT INTO verification_challenges(id,user_id,action,target,code_hash,expires_at)"
            + " VALUES(?,?,?,?,?,now()+interval '5 minutes')",
        id,
        user,
        action,
        target,
        Support.hmac(store.secrets.secret, id + ":" + code));
    store.mail(
        action.equals("email.new") ? target : (String) u.get("email"),
        "MeterTrail verification / 验证码",
        "Code / 验证码: "
            + code
            + "\nAction: "
            + action
            + "\nTarget: "
            + target
            + "\nExpires in 5 minutes. / 5 分钟内有效。");
    store.audit(user, null, "verification.sent", action, true, req);
    return id;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiError.class)
  public void consume(
      UUID user, String action, String target, String id, String code, HttpServletRequest req) {
    var rows =
        store.queries().queryForList(
            "SELECT * FROM verification_challenges WHERE id=? AND user_id=? FOR UPDATE",
            Support.uuid(id),
            user);
    boolean valid = false;
    if (!rows.isEmpty()) {
      var c = rows.get(0);
      valid =
          !(boolean) c.get("consumed")
              && (int) c.get("attempts") < 5
              && ((java.sql.Timestamp) c.get("expires_at"))
                  .toInstant()
                  .isAfter(java.time.Instant.now())
              && action.equals(c.get("action"))
              && target.equals(c.get("target"))
              && Support.equal(
                  (String) c.get("code_hash"), Support.hmac(store.secrets.secret, id + ":" + code));
      store.db.update(
          "UPDATE verification_challenges SET attempts=attempts+1,consumed=consumed OR ? WHERE"
              + " id=?",
          valid,
          Support.uuid(id));
    }
    store.audit(user, null, "verification." + action, target, valid, req);
    if (!valid) throw new ApiError(400, "INVALID_VERIFICATION");
  }
}
