package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"metertrail.secret=test-secret-at-least-32-characters-long",
    "metertrail.worker-enabled=false","metertrail.ingest.pipeline.enabled=false",
    "metertrail.ingest.redis.enabled=true","metertrail.ingest.redis.consumer-enabled=false",
    "metertrail.secure-cookies=false"})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RedisIngestIntegrationTest {
  @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
  @Container static GenericContainer<?> cache=new GenericContainer<>("redis:7-alpine")
      .withExposedPorts(6379).withCommand("sh","-c","while true; do redis-server --appendonly yes --appendfsync always --maxmemory-policy noeviction; sleep 0.1; done");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.data.redis.host",cache::getHost);r.add("spring.data.redis.port",()->cache.getMappedPort(6379));
  }
  @Autowired Store store;
  @Autowired RedisIngest queue;
  @Autowired MockMvc mvc;
  @MockBean RateLimits limits;
  UUID user,workspace,project;
  String key;
  @BeforeEach void fixture() {
    try(var connection=queue.redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
    queue.afterPropertiesSet();
    user=UUID.randomUUID();workspace=UUID.randomUUID();project=UUID.randomUUID();key="lm_live_"+Support.token();
    store.db.update("INSERT INTO users(id,email,verified) VALUES(?,?,true)",user,user+"@test.local");
    store.db.update("INSERT INTO workspaces(id,owner_id,name) VALUES(?,?,'redis-test')",workspace,user);
    store.db.update("INSERT INTO projects(id,workspace_id,name,environment) VALUES(?,?,'redis-test','test')",project,workspace);
    store.db.update("INSERT INTO api_keys(id,project_id,name,key_hash,prefix) VALUES(?,?,'redis-test',?,?)",UUID.randomUUID(),project,Support.hash(key),key.substring(0,16));
  }
  com.fasterxml.jackson.databind.JsonNode body(String object) {
    return Protocol.defaults(Support.parse("{\"events\":[{\"eventId\":\""+UUID.randomUUID()+"\",\"type\":\"trace\",\"objectId\":\""+object+"\",\"traceId\":\"0123456789abcdef0123456789abcdef\",\"startedAt\":\""+Instant.now()+"\",\"data\":{\"name\":\"test\",\"status\":\"success\",\"durationMs\":1}}]}"));
  }
  UUID submit(com.fasterxml.jackson.databind.JsonNode b) throws Exception {
    var response=mvc.perform(post("/v1/events/batch").header("Authorization","Bearer "+key)
        .contentType("application/json").content(b.toString())).andExpect(status().isAccepted()).andReturn();
    return UUID.fromString(Support.parse(response.getResponse().getContentAsString()).path("batchId").asText());
  }
  long units(){return store.db.queryForObject("SELECT used_units FROM workspaces WHERE id=?",Long.class,workspace);}

  @Test void receiptPrecedesBusinessLogicAndDuplicatesDoNotCharge() throws Exception {
    var b=body("one");UUID first=submit(b);
    assertEquals(0,units());assertEquals(0,store.db.queryForObject("SELECT count(*) FROM ingest_batches WHERE project_id=?",Long.class,project));
    mvc.perform(get("/v1/batches/"+first).header("Authorization","Bearer "+key)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pending"));
    assertTrue(queue.processOne());assertEquals(1,units());
    assertEquals(1,queue.status(first,project).get("accepted"));
    new Worker(store,queue.tx,null,true,"test@example.test").process();
    assertEquals("completed",queue.status(first,project).get("status"));
    UUID replay=submit(b);queue.processOne();assertEquals(1,units());assertEquals(1,queue.status(replay,project).get("duplicates"));
    assertEquals(0L,queue.redis.opsForStream().size(RedisIngest.STREAM));
  }
  @Test void quotaAndKeyRevocationAreDatabaseResultsAfter202() throws Exception {
    store.db.update("UPDATE workspaces SET used_units=50000 WHERE id=?",workspace);
    UUID id=submit(body("over"));assertTrue(queue.processOne());
    assertEquals("QUOTA_EXCEEDED",queue.status(id,project).get("code"));assertEquals(50000,units());
    UUID revoked=submit(body("revoked"));store.db.update("UPDATE api_keys SET revoked_at=now() WHERE project_id=?",project);
    queue.processOne();assertEquals("INVALID_API_KEY",queue.status(revoked,project).get("code"));
  }
  @Test void commitBeforeAckCanBeReplayedWithoutChargingTwice() throws Exception {
    UUID id=submit(body("crash"));
    var message=queue.redis.opsForStream().read(Consumer.from(RedisIngest.GROUP,"old-process"),StreamReadOptions.empty().count(1),StreamOffset.create(RedisIngest.STREAM,ReadOffset.lastConsumed())).get(0);
    var crashing=org.mockito.Mockito.spy(queue);
    org.mockito.Mockito.doThrow(new IllegalStateException("simulated crash before ACK")).when(crashing).acknowledge(org.mockito.ArgumentMatchers.any());
    assertThrows(IllegalStateException.class,()->crashing.process(message));
    assertEquals(1L,queue.redis.opsForStream().size(RedisIngest.STREAM));
    queue.process(message);queue.process(message);
    assertEquals(1,units());assertEquals(1L,store.db.queryForObject("SELECT count(*) FROM redis_ingest_results WHERE id=?",Long.class,id));
    assertEquals("0",queue.redis.opsForHash().get(RedisIngest.BUDGET,"requests"));
  }
  @Test void reclaimAbandonedDeliveryAndRedisRestartRetainPayload() throws Exception {
    UUID id=submit(body("restart"));
    // Restart Redis itself: restarting a Docker container can reassign its ephemeral host port.
    assertEquals(0,cache.execInContainer("redis-cli","SHUTDOWN","NOSAVE").getExitCode());
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).ignoreExceptions()
        .until(()->queue.redis.opsForStream().size(RedisIngest.STREAM)==1L);
    var message=queue.redis.opsForStream().read(Consumer.from(RedisIngest.GROUP,"dead-consumer"),StreamReadOptions.empty().count(1),StreamOffset.create(RedisIngest.STREAM,ReadOffset.lastConsumed())).get(0);
    queue.redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
        "redis.call('XCLAIM',KEYS[1],ARGV[1],ARGV[2],0,ARGV[3],'IDLE',61000); return 1",Long.class),
        List.of(RedisIngest.STREAM),RedisIngest.GROUP,"dead-consumer",message.getId().getValue());
    assertTrue(queue.processOne());assertEquals(1,units());assertEquals(1,queue.status(id,project).get("accepted"));
  }
  @Test void pendingReceiptsAreProjectScopedAndBackpressureDoesNotEvict() throws Exception {
    UUID id=submit(body("pending"));assertNull(queue.status(id,UUID.randomUUID()));
    queue.redis.opsForHash().put(RedisIngest.BUDGET,"requests",""+queue.options.maxQueuedRequests());
    mvc.perform(post("/v1/events/batch").header("Authorization","Bearer "+key).contentType("application/json").content(body("full").toString()))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("INGEST_BACKLOG_FULL"));
    assertEquals(1L,queue.redis.opsForStream().size(RedisIngest.STREAM));
  }
  @Test void databaseFailureDoesNotAckOrCreateAFalseResult() throws Exception {
    UUID id=submit(body("db-failure"));
    var message=queue.redis.opsForStream().read(Consumer.from(RedisIngest.GROUP,"retry"),StreamReadOptions.empty().count(1),StreamOffset.create(RedisIngest.STREAM,ReadOffset.lastConsumed())).get(0);
    var failing=org.mockito.Mockito.spy(queue.aggregate);
    org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
        .when(failing).accept(org.mockito.ArgumentMatchers.eq(workspace),org.mockito.ArgumentMatchers.eq(project),org.mockito.ArgumentMatchers.any());
    var transport=new RedisIngest(store,failing,queue.redis,queue.options,queue.tx.getTransactionManager(),queue.metrics);
    assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->transport.process(message));
    assertEquals(0,units());assertEquals("pending",queue.status(id,project).get("status"));
    assertEquals(1L,queue.redis.opsForStream().size(RedisIngest.STREAM));
    queue.process(message);assertEquals(1,units());
  }
  @Test void eventConflictsAreReportedAndHigherRevisionsDoNotCharge() throws Exception {
    var b=body("versioned");submit(b);queue.processOne();
    var event=(com.fasterxml.jackson.databind.node.ObjectNode)b.path("events").get(0);
    ((com.fasterxml.jackson.databind.node.ObjectNode)event.path("data")).put("name","changed");
    UUID conflict=submit(b);queue.processOne();
    var errors=(com.fasterxml.jackson.databind.JsonNode)queue.status(conflict,project).get("errors");
    assertEquals("EVENT_ID_CONFLICT",errors.get(0).path("code").asText());assertEquals(1,units());
    event.put("eventId",UUID.randomUUID().toString());event.put("revision",2);
    UUID update=submit(b);queue.processOne();assertEquals(1,queue.status(update,project).get("accepted"));assertEquals(1,units());
  }
  @Test void concurrentConsumersCannotOversellLastUnit() throws Exception {
    store.db.update("UPDATE workspaces SET used_units=49999 WHERE id=?",workspace);
    UUID a=submit(body("last-a")),b=submit(body("last-b"));
    var messages=queue.redis.opsForStream().read(Consumer.from(RedisIngest.GROUP,"parallel"),StreamReadOptions.empty().count(2),StreamOffset.create(RedisIngest.STREAM,ReadOffset.lastConsumed()));
    var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var tasks=messages.stream().<java.util.concurrent.Callable<Void>>map(m->()->{queue.process(m);return null;}).toList();
      for(var result:executor.invokeAll(tasks))result.get(15,java.util.concurrent.TimeUnit.SECONDS);
    } finally {executor.shutdownNow();}
    assertEquals(50000,units());
    assertEquals(1,((Number)queue.status(a,project).get("accepted")).intValue()+((Number)queue.status(b,project).get("accepted")).intValue());
  }
  @Test void singleEventRequestsShareOneTransactionAndKeepIndependentReceipts() throws Exception {
    var ids=new ArrayList<UUID>();
    for(int i=0;i<40;i++)ids.add(submit(body("group-"+i)));
    assertTrue(queue.processOne());assertEquals(40,units());
    assertEquals(1L,store.db.queryForObject("SELECT count(*) FROM ingest_quota_blocks WHERE project_id=?",Long.class,project));
    for(var id:ids)assertEquals(1,queue.status(id,project).get("accepted"));
    assertEquals(0L,queue.redis.opsForStream().size(RedisIngest.STREAM));
    assertEquals("0",queue.redis.opsForHash().get(RedisIngest.BUDGET,"requests"));
  }
  @Test void groupedQuotaRejectionKeepsEachOriginalBatchResult() throws Exception {
    store.db.update("UPDATE workspaces SET used_units=49998 WHERE id=?",workspace);
    UUID first=submit(body("first")),second=submit(body("second")),third=submit(body("third"));
    queue.processOne();assertEquals(50000,units());
    assertEquals(1,queue.status(first,project).get("accepted"));assertEquals(1,queue.status(second,project).get("accepted"));
    assertEquals("QUOTA_EXCEEDED",queue.status(third,project).get("code"));
    assertEquals(0L,queue.redis.opsForStream().size(RedisIngest.STREAM));
  }
  @Test void groupedCommitBeforeAckReplayIsIdempotent() throws Exception {
    for(int i=0;i<12;i++)submit(body("replay-group-"+i));
    var messages=queue.redis.opsForStream().read(Consumer.from(RedisIngest.GROUP,"crashed-batch"),StreamReadOptions.empty().count(50),StreamOffset.create(RedisIngest.STREAM,ReadOffset.lastConsumed()));
    var crashing=org.mockito.Mockito.spy(queue);
    org.mockito.Mockito.doThrow(new IllegalStateException("batch ACK interrupted")).when(crashing).acknowledgeAll(org.mockito.ArgumentMatchers.any());
    assertThrows(IllegalStateException.class,()->crashing.processMany(messages));assertEquals(12,units());
    assertEquals(12L,queue.redis.opsForStream().size(RedisIngest.STREAM));
    queue.processMany(messages);queue.processMany(messages);assertEquals(12,units());
    assertEquals("0",queue.redis.opsForHash().get(RedisIngest.BUDGET,"events"));
  }
  @Test void groupedConsumptionDoesNotCrossProjects() throws Exception {
    UUID other=UUID.randomUUID();String otherKey="lm_live_"+Support.token();
    store.db.update("INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'other')",other,workspace);
    store.db.update("INSERT INTO api_keys(id,project_id,name,key_hash,prefix) VALUES(?,?,'other',?,'lm_live_other')",UUID.randomUUID(),other,Support.hash(otherKey));
    UUID first=submit(body("same-name"));
    UUID second=(UUID)queue.submit(store.keyScope("Bearer "+otherKey),body("same-name")).get("batchId");
    queue.processOne();assertEquals(2,units());
    assertEquals(1,queue.status(first,project).get("accepted"));assertEquals(1,queue.status(second,other).get("accepted"));
    assertNull(queue.status(first,other));assertNull(queue.status(second,project));
  }
}
