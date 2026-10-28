package com.metertrail;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Redis holds transport data only; every business decision and outcome commits in PostgreSQL. */
@Component
@ConditionalOnProperty(name = "metertrail.ingest.redis.enabled", havingValue = "true")
class RedisIngest implements InitializingBean {
  static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
  static final String STREAM = "mt:{ingest}:stream", BUDGET = "mt:{ingest}:budget", GROUP = "database";
  static final String RECEIPT = "mt:{ingest}:receipt:";
  static final DefaultRedisScript<String> ENQUEUE = new DefaultRedisScript<>("""
      local n=tonumber(ARGV[6]); local bytes=tonumber(ARGV[7]);
      if tonumber(redis.call('HGET',KEYS[2],'requests') or '0')>=tonumber(ARGV[8])
        or tonumber(redis.call('HGET',KEYS[2],'events') or '0')+n>tonumber(ARGV[9])
        or tonumber(redis.call('HGET',KEYS[2],'bytes') or '0')+bytes>tonumber(ARGV[10]) then return '' end
      local sid=redis.call('XADD',KEYS[1],'*','id',ARGV[1],'project',ARGV[2],
        'workspace',ARGV[3],'key',ARGV[4],'payload',ARGV[5],'received',ARGV[6],'bytes',ARGV[7]);
      redis.call('HSET',KEYS[3],'project',ARGV[2],'received',ARGV[6]);
      redis.call('HINCRBY',KEYS[2],'requests',1); redis.call('HINCRBY',KEYS[2],'events',n);
      redis.call('HINCRBY',KEYS[2],'bytes',bytes); return sid;
      """, String.class);
  static final DefaultRedisScript<Long> ACK = new DefaultRedisScript<>("""
      for i=2,#ARGV,3 do
        redis.call('XACK',KEYS[1],ARGV[1],ARGV[i]);
        if redis.call('XDEL',KEYS[1],ARGV[i])==1 then
          redis.call('HINCRBY',KEYS[2],'requests',-1);
          redis.call('HINCRBY',KEYS[2],'events',-tonumber(ARGV[i+1]));
          redis.call('HINCRBY',KEYS[2],'bytes',-tonumber(ARGV[i+2]));
        end
        redis.call('DEL',KEYS[3+(i-2)/3]);
      end
      return 1;
      """, Long.class);
  final Store store;
  final AggregatedIngest aggregate;
  final StringRedisTemplate redis;
  final IngestQueueOptions options;
  final TransactionTemplate tx, nested;
  final MeterRegistry metrics;
  final String consumer = UUID.randomUUID().toString();
  final java.util.concurrent.atomic.AtomicLong queuedRequests=new java.util.concurrent.atomic.AtomicLong(),
      queuedEvents=new java.util.concurrent.atomic.AtomicLong(),queuedBytes=new java.util.concurrent.atomic.AtomicLong();
  @Value("${metertrail.ingest.redis.consumer-enabled:false}") boolean consumerEnabled;

  RedisIngest(Store store, AggregatedIngest aggregate, StringRedisTemplate redis,
      IngestQueueOptions options, PlatformTransactionManager manager, MeterRegistry metrics) {
    this.store=store; this.aggregate=aggregate; this.redis=redis; this.options=options; this.metrics=metrics;
    tx=new TransactionTemplate(manager); nested=new TransactionTemplate(manager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    metrics.gauge("metertrail_redis_ingest_queue_requests",queuedRequests);
    metrics.gauge("metertrail_redis_ingest_queue_events",queuedEvents);
    metrics.gauge("metertrail_redis_ingest_queue_bytes",queuedBytes);
  }

  @Override public void afterPropertiesSet() {
    // Single-node durability requires fsync before accepting a receipt. No silent everysec fallback.
    var config=redis.execute((org.springframework.data.redis.core.RedisCallback<Properties>)
        c -> c.serverCommands().getConfig("append*"));
    if (config==null || !"yes".equals(config.getProperty("appendonly"))
        || !"always".equals(config.getProperty("appendfsync")))
      throw new IllegalStateException("Redis ingestion requires appendonly=yes and appendfsync=always");
    var memory=redis.execute((org.springframework.data.redis.core.RedisCallback<Properties>)
        c -> c.serverCommands().getConfig("maxmemory-policy"));
    if (memory==null || !"noeviction".equals(memory.getProperty("maxmemory-policy")))
      throw new IllegalStateException("Redis ingestion requires maxmemory-policy=noeviction");
    try { redis.opsForStream().createGroup(STREAM, ReadOffset.from("0-0"), GROUP); }
    catch (org.springframework.data.redis.RedisSystemException e) {
      if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains("BUSYGROUP")) throw e;
    }
  }

  Map<String,Object> submit(Store.KeyScope scope, JsonNode body) {
    UUID id=UUID.randomUUID(); String payload=body.toString();
    int count=body.path("events").size(), bytes=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if (bytes>1_048_576) throw new ApiError(413,"PAYLOAD_TOO_LARGE");
    try {
      String sid=redis.execute(ENQUEUE, List.of(STREAM,BUDGET,RECEIPT+id), id.toString(),
          scope.project().toString(),scope.workspace().toString(),scope.hash(),payload,
          ""+count,""+bytes,""+options.maxQueuedRequests(),""+options.maxQueuedEvents(),""+options.maxQueuedBytes());
      if (sid==null || sid.isEmpty()) throw new ApiError(503,"INGEST_BACKLOG_FULL");
      metrics.counter("metertrail_redis_ingest_received").increment(count);
      return Map.of("batchId",id,"received",count,"status","pending");
    } catch (org.springframework.dao.DataAccessException e) {
      throw new ApiError(503,"INGEST_QUEUE_UNAVAILABLE");
    }
  }

  // Null means this is an older PostgreSQL batch: keep its existing status endpoint working.
  Map<String,Object> status(UUID id, UUID project) {
    var saved=store.queries().queryForList("SELECT actual_batch,outcome FROM redis_ingest_results WHERE id=? AND project_id=?",id,project);
    if (!saved.isEmpty()) {
      Object actual=saved.get(0).get("actual_batch");
      if (actual!=null) {
        var batches=store.queries().queryForList("SELECT status,received,accepted,duplicates,rejected,errors FROM ingest_batches WHERE id=? AND project_id=?",actual,project);
        if (batches.isEmpty()) throw new ApiError(404,"BATCH_NOT_FOUND");
        var result=batches.get(0); result.put("errors",Support.parse(result.get("errors").toString())); return result;
      }
      return JSON.convertValue(
          Support.parse(saved.get(0).get("outcome").toString()),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
    }
    if (!store.queries().queryForList("SELECT id FROM ingest_batches WHERE id=? AND project_id=?",id,project).isEmpty()) return null;
    try {
      var receipt=redis.opsForHash().entries(RECEIPT+id);
      if (!project.toString().equals(receipt.get("project"))) return null;
      return Map.of("status","pending","received",Integer.parseInt(receipt.get("received").toString()),
          "accepted",0,"duplicates",0,"rejected",0,"errors",List.of());
    } catch (org.springframework.dao.DataAccessException e) { throw new ApiError(503,"INGEST_QUEUE_UNAVAILABLE"); }
  }

  @Scheduled(fixedDelay=5000)
  void collect() {
    try {
      var budget=redis.opsForHash().entries(BUDGET);
      queuedRequests.set(Long.parseLong(String.valueOf(budget.getOrDefault("requests","0"))));
      queuedEvents.set(Long.parseLong(String.valueOf(budget.getOrDefault("events","0"))));
      queuedBytes.set(Long.parseLong(String.valueOf(budget.getOrDefault("bytes","0"))));
    } catch (RuntimeException e) { metrics.counter("metertrail_redis_ingest_metrics_errors").increment(); }
  }

  @Scheduled(fixedDelayString="${metertrail.ingest.redis.poll-ms:50}")
  void consume() {
    if (!consumerEnabled) return;
    long deadline=System.nanoTime()+Duration.ofMillis(250).toNanos();
    try { for(int n=0;n<200 && System.nanoTime()<deadline;n++) if (!processOne()) break; }
    catch (RuntimeException e) { metrics.counter("metertrail_redis_ingest_retry").increment(); }
  }

  boolean processOne() {
    // Reclaim abandoned deliveries before reading new messages. Database receipt locks fence duplicates.
    var pending=redis.opsForStream().pending(STREAM,GROUP,Range.unbounded(),32);
    if (pending!=null) {
      var abandoned=new ArrayList<RecordId>();
      for(var p:pending) if(p.getElapsedTimeSinceLastDelivery().compareTo(Duration.ofSeconds(60))>=0)abandoned.add(p.getId());
      if(!abandoned.isEmpty()) {
        var reclaimed=redis.opsForStream().claim(STREAM,GROUP,consumer,Duration.ofSeconds(60),abandoned.toArray(RecordId[]::new));
        if(reclaimed!=null && !reclaimed.isEmpty()){processMany(reclaimed);return true;}
      }
    }
    var records=redis.opsForStream().read(Consumer.from(GROUP,consumer),StreamReadOptions.empty().count(Math.min(50,options.writeBatchRequests())),
        StreamOffset.create(STREAM,ReadOffset.lastConsumed()));
    if (records==null || records.isEmpty()) return false;
    int limit=Math.min(50,options.writeBatchRequests());
    if(records.size()<limit && options.coalesceMillis()>0
        && records.stream().mapToInt(m->Integer.parseInt(m.getValue().get("received").toString())).sum()<options.groupEvents()) {
      try {Thread.sleep(Math.min(20,options.coalesceMillis()));}
      catch(InterruptedException interrupted){Thread.currentThread().interrupt();return true;}
      var extra=redis.opsForStream().read(Consumer.from(GROUP,consumer),StreamReadOptions.empty().count(limit-records.size()),
          StreamOffset.create(STREAM,ReadOffset.lastConsumed()));
      if(extra!=null && !extra.isEmpty()){records=new ArrayList<>(records);records.addAll(extra);}
    }
    processMany(records); return true;
  }

  // Keep each project isolated and each database group bounded by events, bytes and requests.
  void processMany(List<MapRecord<String,Object,Object>> messages) {
    var projects=new LinkedHashMap<String,List<MapRecord<String,Object,Object>>>();
    for(var message:messages) {
      var value=message.getValue();
      projects.computeIfAbsent(value.get("workspace")+":"+value.get("project"),ignored->new ArrayList<>()).add(message);
    }
    for(var project:projects.values()) {
      var group=new ArrayList<MapRecord<String,Object,Object>>(); int events=0,bytes=0;
      for(var message:project) {
        int count=Integer.parseInt(message.getValue().get("received").toString());
        int length=Integer.parseInt(message.getValue().get("bytes").toString());
        if(!group.isEmpty() && (events+count>options.groupEvents() || bytes+length>options.flushBytes()
            || group.size()>=options.writeBatchRequests())) {
          processGroup(group); acknowledgeAll(group); group=new ArrayList<>(); events=0;bytes=0;
        }
        group.add(message);events+=count;bytes+=length;
      }
      if(!group.isEmpty()) {processGroup(group);acknowledgeAll(group);}
    }
  }

  void process(MapRecord<String,Object,Object> message) {
    processGroup(List.of(message));
    acknowledge(message);
  }

  void processGroup(List<MapRecord<String,Object,Object>> messages) {
    var ids=messages.stream().map(m->UUID.fromString(m.getValue().get("id").toString())).sorted().toList();
    var first=messages.get(0).getValue();
    UUID project=UUID.fromString(first.get("project").toString()),workspace=UUID.fromString(first.get("workspace").toString());
    tx.executeWithoutResult(transaction -> {
      String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
      // All overlapping deliveries take receipt locks in the same order, before workspace locking.
      store.db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(id::text,0)) FROM (SELECT unnest(ARRAY["
          +String.join(",",Collections.nCopies(ids.size(),"?::uuid"))+ "]) AS id ORDER BY id) ordered",ids.toArray());
      var existing=new HashSet<>(store.db.queryForList("SELECT id FROM redis_ingest_results WHERE id IN ("+placeholders+")",UUID.class,ids.toArray()));
      var fresh=messages.stream().filter(m->!existing.contains(UUID.fromString(m.getValue().get("id").toString()))).toList();
      if(fresh.isEmpty())return;
      Long backlog=store.db.queryForObject("SELECT coalesce(sum(accepted),0) FROM ingest_batches WHERE status IN ('pending','quarantined')",Long.class);
      if(backlog>50000)throw new ApiError(503,"INGEST_BACKLOG_FULL");
      var hashes=fresh.stream().map(m->m.getValue().get("key").toString()).distinct().toList();
      var auth=new ArrayList<Object>();auth.add(project);auth.add(workspace);auth.addAll(hashes);
      var active=new HashSet<>(store.db.queryForList("SELECT k.key_hash FROM api_keys k JOIN projects p ON p.id=k.project_id WHERE p.id=? AND p.workspace_id=? AND p.deleted_at IS NULL AND k.revoked_at IS NULL AND k.key_hash IN ("
          +String.join(",",Collections.nCopies(hashes.size(),"?"))+")",String.class,auth.toArray()));
      var valid=new ArrayList<AggregatedIngest.Request>();
      var results=new LinkedHashMap<UUID,AggregatedIngest.Result>();
      for(var message:fresh) {
        var data=message.getValue();UUID id=UUID.fromString(data.get("id").toString());
        if(active.contains(data.get("key").toString()))valid.add(new AggregatedIngest.Request(id,Support.parse(data.get("payload").toString())));
        else results.put(id,new AggregatedIngest.Result(401,Map.of("code","INVALID_API_KEY")));
      }
      if(!valid.isEmpty()) {
        var accepted=nested.execute(savepoint->aggregate.accept(workspace,project,valid));
        results.putAll(accepted.results());
      }
      var args=new ArrayList<Object>();
      for(var message:fresh) {
        var data=message.getValue();UUID id=UUID.fromString(data.get("id").toString());
        var result=Objects.requireNonNull(results.get(id));
        UUID actual=null;Map<String,Object> outcome=result.body();
        if(result.status()==200)actual=(UUID)outcome.get("batchId");
        else {
          if(result.status()>=500)throw new ApiError(result.status(),String.valueOf(outcome.get("code")));
          int received=Integer.parseInt(data.get("received").toString());String code=String.valueOf(outcome.get("code"));
          var errors=new ArrayList<Map<String,Object>>();
          for(int i=0;i<received;i++)errors.add(Map.of("index",i,"code",code));
          outcome=Map.of("status","completed","received",received,"accepted",0,"duplicates",0,"rejected",received,"errors",errors,"code",code);
        }
        Collections.addAll(args,id,project,actual,JSON.valueToTree(outcome).toString());
      }
      store.db.update("INSERT INTO redis_ingest_results(id,project_id,actual_batch,outcome) VALUES "
          +String.join(",",Collections.nCopies(fresh.size(),"(?,?,?,?::jsonb)")),args.toArray());
      metrics.summary("metertrail_redis_ingest_group_requests").record(fresh.size());
    });
  }

  void acknowledge(MapRecord<String,Object,Object> message) {acknowledgeAll(List.of(message));}

  void acknowledgeAll(List<MapRecord<String,Object,Object>> messages) {
    var keys=new ArrayList<String>();keys.add(STREAM);keys.add(BUDGET);
    var args=new ArrayList<String>();args.add(GROUP);
    for(var message:messages) {
      var data=message.getValue();keys.add(RECEIPT+data.get("id"));
      Collections.addAll(args,message.getId().getValue(),data.get("received").toString(),data.get("bytes").toString());
    }
    redis.execute(ACK,keys,args.toArray());
  }
}
