package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Resolves immutable prices; never updates existing cost evidence. */
final class ModelPricing {
  static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
  private ModelPricing() {}
  record Quote(UUID version, BigDecimal cost, String rates) {}

  static Quote quote(Store store, UUID project, JsonNode data, Instant started) {
    String provider = data.path("provider").asText(), model = data.path("model").asText();
    JsonNode usage = data.path("usage");
    if (provider.isEmpty() || model.isEmpty()
        || !usage.path("inputTokens").isIntegralNumber()
        || !usage.path("outputTokens").isIntegralNumber()
        || !usage.path("cachedInputTokens").isIntegralNumber()) return null;
    var prices = store.queries().queryForList(
        "SELECT pv.* FROM price_versions pv WHERE provider=? AND model=? AND effective_at<=?"
        + " AND (project_id=? OR (project_id IS NULL AND EXISTS(SELECT 1 FROM projects p"
        + " WHERE p.id=? AND p.public_pricing)))"
        + " ORDER BY (project_id IS NOT NULL) DESC,effective_at DESC LIMIT 1",
        provider, model, Timestamp.from(started), project, project);
    if (prices.isEmpty() || !Boolean.TRUE.equals(prices.get(0).get("available"))) return null;
    var price = prices.get(0);
    JsonNode bands = Support.parse(price.get("time_bands").toString());
    BigDecimal input = (BigDecimal) price.get("input_rate"), output = (BigDecimal) price.get("output_rate");
    BigDecimal cached = (BigDecimal) price.get("cached_rate");
    for (JsonNode band : bands) {
      int minute = started.atZone(ZoneId.of(band.path("timezone").asText())).toLocalTime().toSecondOfDay() / 60;
      int from = minute(band.path("start").asText()), to = minute(band.path("end").asText());
      if (contains(minute, from, to)) {
        input = band.path("inputRate").decimalValue(); output = band.path("outputRate").decimalValue();
        cached = band.path("cachedRate").decimalValue(); break;
      }
    }
    long in = usage.path("inputTokens").asLong(), out = usage.path("outputTokens").asLong();
    long hit = usage.path("cachedInputTokens").asLong();
    if (in < 0 || out < 0 || hit < 0 || hit > in || (hit>0 && !Boolean.TRUE.equals(price.get("cached_known")))) return null;
    BigDecimal cost = BigDecimal.valueOf(in-hit).multiply(input)
        .add(BigDecimal.valueOf(out).multiply(output)).add(BigDecimal.valueOf(hit).multiply(cached))
        .divide(BigDecimal.valueOf(1_000_000));
    String rates = JSON.valueToTree(Map.of("input",input,"output",output,"cached",cached)).toString();
    return new Quote((UUID) price.get("id"),cost,rates);
  }

  static int minute(String value) {
    if ("24:00".equals(value)) return 1440;
    if (!value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) throw new IllegalArgumentException("Invalid time");
    return LocalTime.parse(value).toSecondOfDay()/60;
  }
  static boolean contains(int minute, int from, int to) {
    return from < to ? minute >= from && minute < to : minute >= from || minute < to;
  }
  static BigDecimal rate(JsonNode value) {
    if (!value.isNumber()) throw new IllegalArgumentException("Missing rate");
    BigDecimal n = value.decimalValue();
    if (n.signum()<0 || n.compareTo(new BigDecimal("1000000"))>0 || n.scale()>12)
      throw new IllegalArgumentException("Invalid rate");
    return n;
  }
  static ArrayNode bands(JsonNode value) {
    ArrayNode result = JSON.createArrayNode();
    if (value.isMissingNode()) return result;
    if (!value.isArray() || value.size()>24) throw new IllegalArgumentException("Invalid time bands");
    boolean[] occupied = new boolean[1440];
    String timezone = null;
    for (JsonNode band:value) {
      String zone = band.path("timezone").asText(); ZoneId.of(zone);
      if (timezone != null && !timezone.equals(zone)) throw new IllegalArgumentException("Mixed timezones");
      timezone = zone;
      int from=minute(band.path("start").asText()),to=minute(band.path("end").asText());
      if (from==to || from==1440) throw new IllegalArgumentException("Empty time band");
      for (int m=0;m<1440;m++) if (contains(m,from,to)) {
        if (occupied[m]) throw new IllegalArgumentException("Overlapping time bands"); occupied[m]=true;
      }
      var normalized = result.addObject();
      normalized.put("timezone",zone); normalized.put("start",band.path("start").asText());
      normalized.put("end",band.path("end").asText());
      for (String key:List.of("inputRate","outputRate","cachedRate")) normalized.put(key,rate(band.path(key)));
    }
    return result;
  }
}
