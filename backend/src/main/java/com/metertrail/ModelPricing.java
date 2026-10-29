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
    Quote q=resolve(store,project,data,started);
    return q.cost()==null?null:q;
  }
  static Quote unknown(String reason) {
    return new Quote(null,null,JSON.valueToTree(Map.of("reason",reason,"currency","USD")).toString());
  }
  static Quote resolve(Store store, UUID project, JsonNode data, Instant started) {
    String provider = data.path("provider").asText(), model = data.path("model").asText();
    JsonNode usage = data.path("usage");
    if (provider.isEmpty() || model.isEmpty()
        || !usage.path("inputTokens").isIntegralNumber()
        || !usage.path("outputTokens").isIntegralNumber()
        || !usage.path("cachedInputTokens").isIntegralNumber()) return unknown("usage_missing");
    var prices = store.queries().queryForList(
        "SELECT pv.* FROM price_versions pv WHERE provider=? AND model=? AND effective_at<=?"
        + " AND (project_id=? OR (project_id IS NULL AND EXISTS(SELECT 1 FROM projects p"
        + " WHERE p.id=? AND p.public_pricing)))"
        + " ORDER BY (project_id IS NOT NULL) DESC,effective_at DESC LIMIT 1",
        provider, model, Timestamp.from(started), project, project);
    if (prices.isEmpty()) return unknown("price_missing");
    if (!Boolean.TRUE.equals(prices.get(0).get("available"))) return unknown("price_unavailable");
    var price = prices.get(0);
    if(!"USD".equals(price.get("currency")))return unknown("currency_unsupported");
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
    if (in < 0 || out < 0 || hit < 0 || hit > in) return unknown("usage_missing");
    long write5=usage.path("cacheWrite5mTokens").asLong(),write1=usage.path("cacheWrite1hTokens").asLong();
    for(String k:List.of("cacheWrite5mTokens","cacheWrite1hTokens"))
      if(usage.has(k)&&!usage.path(k).isIntegralNumber())return unknown("usage_missing");
    if(hit+write5+write1>in)return unknown("usage_missing");
    JsonNode context=data.path("billing"),rules=Support.parse(price.get("billing_rules").toString());
    JsonNode rule=PricingRules.select(rules,context,in);
    boolean special=context.path("batch").asBoolean()||!context.path("serviceTier").asText("standard").equals("standard")||!context.path("region").asText("global").equals("global");
    if(special&&rule==null)return unknown("billing_rule_missing");
    if(rule!=null&&!bands.isEmpty())return unknown("billing_rule_ambiguous");
    if(rule!=null) {
      input=rule.path("inputRate").decimalValue();output=rule.path("outputRate").decimalValue();cached=rule.path("cachedRate").decimalValue();
    }
    if(hit>0&&rule==null&&!Boolean.TRUE.equals(price.get("cached_known")))return unknown("cache_rate_missing");
    if((write5>0&&(rule==null||!rule.has("cacheWrite5mRate")))||(write1>0&&(rule==null||!rule.has("cacheWrite1hRate"))))return unknown("cache_write_rate_missing");
    BigDecimal w5=rule==null?BigDecimal.ZERO:rule.path("cacheWrite5mRate").decimalValue();
    BigDecimal w1=rule==null?BigDecimal.ZERO:rule.path("cacheWrite1hRate").decimalValue();
    BigDecimal cost = BigDecimal.valueOf(in-hit-write5-write1).multiply(input)
        .add(BigDecimal.valueOf(out).multiply(output)).add(BigDecimal.valueOf(hit).multiply(cached))
        .add(BigDecimal.valueOf(write5).multiply(w5)).add(BigDecimal.valueOf(write1).multiply(w1))
        .divide(BigDecimal.valueOf(1_000_000));
    var evidence=JSON.createObjectNode();evidence.put("input",input.stripTrailingZeros());evidence.put("output",output.stripTrailingZeros());evidence.put("cached",cached.stripTrailingZeros());
    evidence.put("cacheWrite5m",w5);evidence.put("cacheWrite1h",w1);evidence.put("currency","USD");
    evidence.put("source",price.get("source").toString()); evidence.put("matchedProvider",provider);evidence.put("matchedModel",model);
    evidence.put("effectiveAt",price.get("effective_at").toString());evidence.put("matchedAt",started.toString());
    if(rule!=null)evidence.set("billingRule",rule);
    String rates = evidence.toString();
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
