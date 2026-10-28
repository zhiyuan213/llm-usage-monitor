package com.metertrail;

import java.util.Map;
import java.util.Set;

final class SubscriptionState {
  static boolean ended(Map<String,Object> workspace) {
    return Set.of("canceled","expired").contains(String.valueOf(workspace.get("subscription_status")));
  }
}
