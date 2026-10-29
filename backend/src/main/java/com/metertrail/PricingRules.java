package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.*;

/** Explicit full rate cards: no guessed discounts, model aliases or regional multipliers. */
final class PricingRules {
  static final Set<String> RATES=Set.of("inputRate","outputRate","cachedRate","cacheWrite5mRate","cacheWrite1hRate");
  static void context(JsonNode c) {
    Support.require(c.isObject(),"INVALID_BILLING_CONTEXT");
    c.fieldNames().forEachRemaining(k->Support.require(Set.of("batch","serviceTier","region").contains(k),"INVALID_BILLING_CONTEXT"));
    if(c.has("batch"))Support.require(c.path("batch").isBoolean(),"INVALID_BILLING_CONTEXT");
    for(String k:List.of("serviceTier","region"))if(c.has(k))Support.text(c,k,64);
  }
  static ArrayNode validate(JsonNode rules) {
    if(rules.isMissingNode())return ModelPricing.JSON.createArrayNode();
    Support.require(rules.isArray()&&rules.size()<=64,"INVALID_PRICE_RULES");
    Set<String> selectors=new HashSet<>();
    for(JsonNode r:rules) {
      r.fieldNames().forEachRemaining(k->Support.require(RATES.contains(k)||Set.of("batch","serviceTier","region","minInputTokens").contains(k),"INVALID_PRICE_RULES"));
      Support.require(r.isObject()&&r.path("batch").isBoolean(),"INVALID_PRICE_RULES");
      for(String k:List.of("serviceTier","region"))Support.text(r,k,64);
      Protocol.nonnegative(r.path("minInputTokens"),false,"INVALID_PRICE_RULES");
      for(String k:List.of("inputRate","outputRate","cachedRate"))ModelPricing.rate(r.path(k));
      for(String k:List.of("cacheWrite5mRate","cacheWrite1hRate"))if(r.has(k))ModelPricing.rate(r.path(k));
      Support.require(selectors.add(r.path("batch")+"/"+r.path("serviceTier")+"/"+r.path("region")+"/"+r.path("minInputTokens")),"INVALID_PRICE_RULES");
    }
    return (ArrayNode)rules;
  }
  static JsonNode select(JsonNode rules,JsonNode context,long input) {
    JsonNode selected=null;
    for(JsonNode r:rules)if(r.path("batch").asBoolean()==context.path("batch").asBoolean()
        &&r.path("serviceTier").asText().equals(context.path("serviceTier").asText("standard"))
        &&r.path("region").asText().equals(context.path("region").asText("global"))
        &&r.path("minInputTokens").asLong()<=input
        &&(selected==null||r.path("minInputTokens").asLong()>selected.path("minInputTokens").asLong()))selected=r;
    return selected;
  }
}
