package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded background import. Network failures leave the last valid catalogue intact. */
@Component
class ModelPriceSync {
  final Store store;
  final TransactionTemplate tx;
  final boolean enabled;
  final String url;
  final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  ModelPriceSync(Store store, TransactionTemplate tx,
      @Value("${metertrail.worker-enabled}") boolean worker,
      @Value("${metertrail.pricing.sync-enabled:true}") boolean enabled,
      @Value("${metertrail.pricing.catalog-url:https://models.dev/api.json}") String url) {
    this.store=store; this.tx=tx; this.enabled=worker && enabled; this.url=url;
  }
  @Scheduled(scheduler="modelPriceScheduler", initialDelayString="${metertrail.pricing.initial-delay-ms:15000}",
      fixedDelayString="${metertrail.pricing.sync-interval-ms:21600000}")
  void sync() {
    if (!enabled) return;
    try {
      URI uri=URI.create(url);
      if (!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || uri.getQuery()!=null) throw new IllegalArgumentException("Public HTTPS source required");
      var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
          .header("Accept","application/json").GET().build(), info -> new LimitedBody());
      if(response.statusCode()!=200) throw new IllegalStateException("Price source HTTP error");
      byte[] bytes=response.body();
      apply(Support.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)),Instant.now());
    } catch (Exception ex) {
      if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
      store.db.update("UPDATE model_price_sync SET checked_at=now(),status='failed' WHERE id=1");
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Model price sync failed ({})",ex.getClass().getSimpleName());
    }
  }
  static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
    java.util.concurrent.Flow.Subscription subscription;
    int bytes;
    public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    public void onSubscribe(java.util.concurrent.Flow.Subscription s) { subscription=s;delegate.onSubscribe(s); }
    public void onNext(List<java.nio.ByteBuffer> buffers) {
      for(var buffer:buffers) bytes+=buffer.remaining();
      if(bytes>8_000_000) { subscription.cancel();delegate.onError(new IllegalArgumentException("Price source too large")); }
      else delegate.onNext(buffers);
    }
    public void onError(Throwable error) { delegate.onError(error); }
    public void onComplete() { delegate.onComplete(); }
  }
  record Entry(String provider,String model,BigDecimal input,BigDecimal output,BigDecimal cached,
      boolean cachedKnown,String bands,Instant effective) {}

  List<Entry> parse(JsonNode root,Instant fetched) {
    var result=new ArrayList<Entry>();
    if(root.has("prices")) {
      if(root.path("schemaVersion").asInt()!=1 || !root.path("prices").isArray()) throw new IllegalArgumentException("Invalid catalogue");
      for(JsonNode p:root.path("prices")) {
        String provider=identifier(p.path("provider"),64),model=identifier(p.path("model"),256);
        Instant effective=Instant.parse(p.path("effectiveAt").asText());
        result.add(new Entry(provider,model,ModelPricing.rate(p.path("inputRate")),ModelPricing.rate(p.path("outputRate")),
            ModelPricing.rate(p.path("cachedRate")),true,ModelPricing.bands(p.path("timeBands")).toString(),effective));
      }
    } else {
      if(!root.isObject() || !root.path("openai").path("models").isObject()) throw new IllegalArgumentException("Invalid models.dev catalogue");
      root.fields().forEachRemaining(provider -> {
        if(!provider.getValue().path("models").isObject()) return;
        provider.getValue().path("models").fields().forEachRemaining(model -> {
          try {
          JsonNode cost=model.getValue().path("cost");
          if(!cost.path("input").isNumber() || !cost.path("output").isNumber()) return;
          // Reject pricing dimensions that this token protocol cannot represent safely.
          var keys=new HashSet<String>(); cost.fieldNames().forEachRemaining(keys::add);
          if(!Set.of("input","output","cache_read","reasoning").containsAll(keys)) return;
          if(cost.has("reasoning") && !cost.path("reasoning").equals(cost.path("output"))) return;
          BigDecimal input=ModelPricing.rate(cost.path("input")),output=ModelPricing.rate(cost.path("output"));
          if(input.signum()==0 && output.signum()==0) return; // Unconfirmed/free placeholders aren't paid prices.
          result.add(new Entry(identifier(ModelPricing.JSON.valueToTree(provider.getKey()),64),
              identifier(ModelPricing.JSON.valueToTree(model.getKey()),256),input,output,
              cost.has("cache_read")?ModelPricing.rate(cost.path("cache_read")):BigDecimal.ZERO,
              cost.has("cache_read"),"[]",fetched));
          } catch (IllegalArgumentException unsupported) {
            // An unusable individual model must not prevent valid models from refreshing.
            // It is absent from this snapshot and any old public price gets a tombstone.
          }
        });
      });
    }
    if(result.isEmpty() || result.size()>20000) throw new IllegalArgumentException("Empty or oversized catalogue");
    var identities=new HashSet<String>();
    for(var e:result) if(!identities.add(e.provider()+"\n"+e.model()+"\n"+e.effective())) throw new IllegalArgumentException("Duplicate price");
    result.sort(Comparator.comparing(Entry::effective));
    return result;
  }
  static String identifier(JsonNode node,int max) {
    String value=node.asText();
    if(!node.isTextual() || value.isBlank() || value.length()>max || value.indexOf('\n')>=0) throw new IllegalArgumentException("Invalid model identity");
    return value;
  }
  void apply(JsonNode root,Instant fetched) {
    var entries=parse(root,fetched);
    tx.executeWithoutResult(status -> {
      store.db.execute("SELECT pg_advisory_xact_lock(73629104)");
      var seen=new HashSet<String>();
      for(var e:entries) {
        seen.add(e.provider()+"\n"+e.model());
        var exact=store.db.queryForList("SELECT * FROM price_versions WHERE project_id IS NULL AND provider=? AND model=? AND effective_at=?",e.provider(),e.model(),Timestamp.from(e.effective()));
        if(!exact.isEmpty()) {
          var p=exact.get(0);
          if(!same(p,e)) throw new IllegalArgumentException("Published price versions are immutable; use a new effectiveAt");
          continue;
        }
        var old=store.db.queryForList("SELECT * FROM price_versions WHERE project_id IS NULL AND provider=? AND model=? ORDER BY effective_at DESC LIMIT 1",e.provider(),e.model());
        if(!old.isEmpty()) {
          var p=old.get(0);
          if(same(p,e) && !root.has("prices")) continue;
        }
        store.db.update("INSERT INTO price_versions(id,provider,model,effective_at,input_rate,output_rate,cached_rate,source,time_bands,fetched_at,cached_known) VALUES(?,?,?,?,?,?,?, ?,?::jsonb,?,?) ON CONFLICT DO NOTHING",
            UUID.randomUUID(),e.provider(),e.model(),Timestamp.from(e.effective()),e.input(),e.output(),e.cached(),url,e.bands(),Timestamp.from(fetched),e.cachedKnown());
      }
      // Removed/unsupported models stop being priced for future calls, without erasing old evidence.
      var latest=store.db.queryForList("SELECT DISTINCT ON(provider,model) * FROM price_versions WHERE project_id IS NULL ORDER BY provider,model,effective_at DESC");
      for(var p:latest) if(Boolean.TRUE.equals(p.get("available")) && !seen.contains(p.get("provider")+"\n"+p.get("model")))
        store.db.update("INSERT INTO price_versions(id,provider,model,effective_at,input_rate,output_rate,cached_rate,source,available,fetched_at) VALUES(?,?,?,?,0,0,0,?,false,?) ON CONFLICT DO NOTHING",
            UUID.randomUUID(),p.get("provider"),p.get("model"),Timestamp.from(fetched),url,Timestamp.from(fetched));
      store.db.update("UPDATE model_price_sync SET checked_at=?,succeeded_at=?,status='ok',model_count=? WHERE id=1",Timestamp.from(fetched),Timestamp.from(fetched),seen.size());
    });
  }
  static boolean same(Map<String,Object> p,Entry e) {
    return Boolean.TRUE.equals(p.get("available")) && e.input().compareTo((BigDecimal)p.get("input_rate"))==0
        && e.output().compareTo((BigDecimal)p.get("output_rate"))==0 && e.cached().compareTo((BigDecimal)p.get("cached_rate"))==0
        && e.cachedKnown()==Boolean.TRUE.equals(p.get("cached_known"))
        && Support.parse(p.get("time_bands").toString()).equals(Support.parse(e.bands()));
  }
}

@Configuration
class ModelPriceScheduling {
  @Bean(name="modelPriceScheduler")
  ThreadPoolTaskScheduler modelPriceScheduler() {
    var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("model-prices-"); return scheduler;
  }
}
