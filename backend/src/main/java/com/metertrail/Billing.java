package com.metertrail;

import java.math.BigDecimal;

final class Billing {
  static long included(String plan) {
    return plan.equals("Free") ? 50_000 : 100_000;
  }

  static int retention(String plan) {
    return switch (plan) {
      case "Pro" -> 1096;
      case "Core" -> 90;
      default -> 30;
    };
  }

  static BigDecimal overage(long used) {
    long[] edges = {100_000, 1_000_000, 10_000_000, 50_000_000, Long.MAX_VALUE};
    String[] rates = {"8", "7", "6.5", "6"};
    BigDecimal total = BigDecimal.ZERO;
    for (int i = 0; i < 4; i++) {
      long count = Math.max(0, Math.min(used, edges[i + 1]) - edges[i]);
      total =
          total.add(
              BigDecimal.valueOf(count)
                  .multiply(new BigDecimal(rates[i]))
                  .divide(BigDecimal.valueOf(100_000)));
    }
    return total;
  }
}
