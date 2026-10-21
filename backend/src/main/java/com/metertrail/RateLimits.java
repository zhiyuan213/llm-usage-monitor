package com.metertrail;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
class RateLimits {
  final StringRedisTemplate redis;

  RateLimits(StringRedisTemplate redis) {
    this.redis = redis;
  }

  void check(String key, int limit) {
    try {
      Long count =
          redis.execute(
              new DefaultRedisScript<Long>(
                  "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60)"
                      + " end; return n",
                  Long.class),
              List.of("mt:" + key));
      if (count != null && count > limit) throw new ApiError(429, "RATE_LIMITED");
    } catch (ApiError e) {
      throw e;
    } catch (Exception e) {
      throw new ApiError(503, "RATE_LIMIT_UNAVAILABLE");
    }
  }
}
