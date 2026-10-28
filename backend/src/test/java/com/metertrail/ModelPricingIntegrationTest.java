package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"metertrail.secret=test-secret-at-least-32-characters-long",
    "metertrail.worker-enabled=false","metertrail.ingest.pipeline.enabled=false","metertrail.secure-cookies=false"})
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ModelPricingIntegrationTest {
  @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);r.add("spring.datasource.password",pg::getPassword);
  }
  @Autowired Store store;
  @Autowired TransactionTemplate tx;
  @Autowired MockMvc mvc;
  @Autowired IngestService ingest;
  @Autowired MailDelivery mail;
  @MockBean RateLimits limits;
  UUID project,user;
  Cookie cookie;
  String provider;
  final Instant t=Instant.parse("2026-10-26T00:00:00Z");
  ModelPriceSync sync;
  @BeforeEach void setup() {
    user=UUID.randomUUID();UUID workspace=UUID.randomUUID();project=UUID.randomUUID();provider="public-"+project;
    store.db.update("INSERT INTO users(id,email,verified) VALUES(?,?,true)",user,user+"@test.local");
    store.db.update("INSERT INTO workspaces(id,owner_id,name) VALUES(?,?,'Prices')",workspace,user);
    store.db.update("INSERT INTO projects(id,workspace_id,name) VALUES(?,?,'Prices')",project,workspace);
    String token=Support.token();cookie=new Cookie("mt_session",token);
    store.db.update("INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,now()+interval '1 hour')",Support.hash(token),user,"prices");
    sync=new ModelPriceSync(store,tx,false,true,"https://catalog.example/prices.json");
  }
  JsonNode feed(String rate,Instant effective,String bands) {
    return Support.parse("{\"schemaVersion\":1,\"prices\":[{\"provider\":\""+provider+"\",\"model\":\"shared\",\"effectiveAt\":\""+effective+"\",\"inputRate\":"+rate+",\"outputRate\":4,\"cachedRate\":0.5,\"timeBands\":"+bands+"}]}");
  }
  ObjectNode data() {
    return (ObjectNode)Support.parse("{\"observationType\":\"generation\",\"provider\":\""+provider+"\",\"model\":\"shared\",\"usage\":{\"inputTokens\":1000,\"outputTokens\":200,\"cachedInputTokens\":300}}");
  }
  void cost(String expected,Instant started) {
    var q=ModelPricing.quote(store,project,data(),started);assertNotNull(q);
    assertEquals(0,new BigDecimal(expected).compareTo(q.cost()));
  }
  @Test void publicVersionsOverridesAndPrivateIsolation() {
    sync.apply(feed("2",t,"[]"),t);cost("0.00235",t);
    sync.apply(feed("3",t.plusSeconds(3600),"[]"),t.plusSeconds(3600));
    cost("0.00235",t.plusSeconds(3599));cost("0.00305",t.plusSeconds(3600));
    store.db.update("INSERT INTO price_versions(id,project_id,provider,model,effective_at,input_rate,output_rate,cached_rate) VALUES(?,?,?,?,?,10,20,1)",UUID.randomUUID(),project,provider,"shared",java.sql.Timestamp.from(t));
    cost("0.0113",t.plusSeconds(7200));
    var privateData=data();privateData.put("provider","private-provider");
    assertNull(ModelPricing.quote(store,project,privateData,t));
    store.db.update("UPDATE projects SET public_pricing=false WHERE id=?",project);
    cost("0.0113",t); // User override still applies with public lookup disabled.
    store.db.update("DELETE FROM price_versions WHERE project_id=?",project);
    assertNull(ModelPricing.quote(store,project,data(),t));
  }
  @Test void overnightTimezoneAndHalfOpenBoundaries() {
    String bands="[{\"timezone\":\"Asia/Shanghai\",\"start\":\"22:00\",\"end\":\"06:00\",\"inputRate\":1,\"outputRate\":2,\"cachedRate\":0.1}]";
    sync.apply(feed("2",t.minusSeconds(86400),bands),t);
    cost("0.00235",Instant.parse("2026-10-26T13:59:59Z"));
    cost("0.00113",Instant.parse("2026-10-26T14:00:00Z"));
    cost("0.00113",Instant.parse("2026-10-26T21:59:59Z"));
    cost("0.00235",Instant.parse("2026-10-26T22:00:00Z"));
    var q=ModelPricing.quote(store,project,data(),Instant.parse("2026-10-26T15:00:00Z"));
    assertEquals(1,Support.parse(q.rates()).path("input").asInt());
    String overlap=bands.substring(0,bands.length()-1)+","+bands.substring(1);
    assertThrows(IllegalArgumentException.class,()->ModelPricing.bands(Support.parse(overlap)));
    assertThrows(Exception.class,()->ModelPricing.bands(Support.parse(bands.replace("Asia/Shanghai","Not/AZone"))));
  }
  @Test void syncIsIdempotentRejectsInvalidAndDoesNotRewriteHistoricalEvidence() {
    Instant started=Instant.now().minusSeconds(10);
    sync.apply(feed("2",started.minusSeconds(1),"[]"),started);
    var quote=ModelPricing.quote(store,project,data(),started);
    store.db.update("INSERT INTO objects(project_id,type,object_id,revision,started_at,data,cost,price_version,applied_rates) VALUES(?,'observation','old',1,?,?::jsonb,?,?,?::jsonb)",project,java.sql.Timestamp.from(started),data().toString(),quote.cost(),quote.version(),quote.rates());
    sync.apply(feed("3",started.plusSeconds(1),"[]"),started.plusSeconds(1));
    sync.apply(feed("3",started.plusSeconds(1),"[]"),started.plusSeconds(2));
    assertEquals(2,store.db.queryForObject("SELECT count(*) FROM price_versions WHERE provider=?",Integer.class,provider));
    assertEquals(0,new BigDecimal("0.00235").compareTo(store.db.queryForObject("SELECT cost FROM objects WHERE project_id=?",BigDecimal.class,project)));
    assertThrows(Exception.class,()->sync.apply(feed("-1",t,"[]"),t));
    cost("0.00305",started.plusSeconds(3));
    mvcCheckEvidence();
  }
  void mvcCheckEvidence() {
    // Evidence IDs and rates are immutable even though the catalogue now has a newer price.
    assertEquals(2,store.db.queryForObject("SELECT (applied_rates->>'input')::int FROM objects WHERE project_id=?",Integer.class,project));
  }
  @Test void actualWorkerUsesPriceAndPersistsMatchedTimeBandRates() {
    Instant now=Instant.now();
    String band="[{\"timezone\":\"UTC\",\"start\":\"00:00\",\"end\":\"24:00\",\"inputRate\":1,\"outputRate\":2,\"cachedRate\":0.1}]";
    sync.apply(feed("2",now.minusSeconds(60),band),now);
    ObjectNode event=ModelPricing.JSON.createObjectNode();
    event.put("eventId",UUID.randomUUID().toString()).put("type","observation").put("objectId","worker-price").put("revision",1).put("startedAt",now.toString())
        .put("traceId","0123456789abcdef0123456789abcdef").put("spanId","0123456789abcdef");
    var d=data();d.put("status","success").put("durationMs",10);event.set("data",d);
    ObjectNode body=ModelPricing.JSON.createObjectNode();body.put("schemaVersion","1.0");
    body.putObject("agent").put("serviceName","test").put("environment","test").put("instanceId","test");body.putArray("events").add(event);
    ingest.accept(project,body);new Worker(store,tx,mail,true,"test@test.local").process();
    assertEquals(0,new BigDecimal("0.00113").compareTo(store.db.queryForObject("SELECT cost FROM objects WHERE project_id=?",BigDecimal.class,project)));
    assertEquals(1,store.db.queryForObject("SELECT (applied_rates->>'input')::int FROM cost_entries WHERE project_id=?",Integer.class,project));
    sync.apply(feed("4",now.plusSeconds(1),"[]"),now.plusSeconds(1));
    new Worker(store,tx,mail,true,"test@test.local").process();
    assertEquals(0,new BigDecimal("0.00113").compareTo(store.db.queryForObject("SELECT cost FROM objects WHERE project_id=?",BigDecimal.class,project)));
  }
  @Test void apiPolicyAndTimeBandsValidateOwnership() throws Exception {
    String path="/api/projects/"+project;
    mvc.perform(get(path+"/pricing-status").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.public_pricing").value(true));
    mvc.perform(post(path+"/pricing-policy").cookie(cookie).header("X-CSRF-Token","prices").contentType("application/json").content("{\"publicPricing\":false}"))
        .andExpect(status().isOk());
    mvc.perform(get(path+"/pricing-status").cookie(cookie)).andExpect(jsonPath("$.public_pricing").value(false));
    mvc.perform(get("/api/projects/"+UUID.randomUUID()+"/pricing-status").cookie(cookie)).andExpect(status().isNotFound());
    mvc.perform(post(path+"/pricing-policy").cookie(cookie).contentType("application/json").content("{\"publicPricing\":true}"))
        .andExpect(status().isForbidden());
    var price=(ObjectNode)feed("2",t,"[]").path("prices").get(0);
    mvc.perform(post(path+"/prices").cookie(cookie).header("X-CSRF-Token","prices").contentType("application/json").content(price.toString())).andExpect(status().isOk());
    price.putArray("timeBands").addObject().put("timezone","bad");
    mvc.perform(post(path+"/prices").cookie(cookie).header("X-CSRF-Token","prices").contentType("application/json").content(price.toString())).andExpect(status().isBadRequest());
  }
  @Test void modelsDevMissingCacheAndUnsupportedDimensionsAreNotGuessed() {
    JsonNode root=Support.parse("{\"openai\":{\"models\":{\"simple\":{\"cost\":{\"input\":2,\"output\":4}},\"tiered\":{\"cost\":{\"input\":2,\"output\":4,\"input_over_200k\":5}},\"invalid\":{\"cost\":{\"input\":-1,\"output\":2}},\"placeholder\":{\"cost\":{\"input\":0,\"output\":0}}}}}");
    var entries=sync.parse(root,t);assertEquals(1,entries.size());assertFalse(entries.get(0).cachedKnown());
    sync.apply(root,t);var d=data();d.put("provider","openai").put("model","simple");
    assertNull(ModelPricing.quote(store,project,d,t));
    ((ObjectNode)d.path("usage")).put("cachedInputTokens",0);
    assertNotNull(ModelPricing.quote(store,project,d,t));
    assertThrows(Exception.class,()->sync.apply(Support.parse("{}"),t));
  }
  @Test void publicCatalogueSearchPaginationVersionsAndOwnership() throws Exception {
    for(int i=0;i<27;i++)store.db.update("INSERT INTO price_versions(id,provider,model,effective_at,input_rate,output_rate,cached_rate,source,time_bands) VALUES(?,?,?,?,2,4,0.003,'https://prices.example',?::jsonb)",
        UUID.randomUUID(),provider,String.format("model-%02d",i),java.sql.Timestamp.from(t),"[]");
    store.db.update("INSERT INTO price_versions(id,provider,model,effective_at,input_rate,output_rate,cached_rate) VALUES(?,?,?,?,3,6,0.001)",UUID.randomUUID(),provider,"model-00",java.sql.Timestamp.from(t.plusSeconds(60)));
    store.db.update("INSERT INTO price_versions(id,provider,model,effective_at,input_rate,output_rate,cached_rate) VALUES(?,?,?,?,99,99,99)",UUID.randomUUID(),provider,"model-00",java.sql.Timestamp.from(Instant.now().plusSeconds(86400)));
    store.db.update("INSERT INTO price_versions(id,provider,model,effective_at,input_rate,output_rate,cached_rate,available) VALUES(?,?,?,?,0,0,0,false)",UUID.randomUUID(),provider,"model-26",java.sql.Timestamp.from(t.plusSeconds(60)));
    store.db.update("UPDATE price_versions SET cached_known=false WHERE provider=? AND model='model-25'",provider);
    store.db.update("INSERT INTO price_versions(id,project_id,provider,model,effective_at,input_rate,output_rate,cached_rate) VALUES(?,?,?,?,?,10,10,1)",UUID.randomUUID(),project,provider,"model-00",java.sql.Timestamp.from(t));
    String base="/api/projects/"+project+"/public-prices";
    mvc.perform(get(base).cookie(cookie).param("provider",provider).param("size","10"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(26)).andExpect(jsonPath("$.items.length()").value(10))
        .andExpect(jsonPath("$.items[0].model").value("model-00")).andExpect(jsonPath("$.items[0].input_rate").value(3))
        .andExpect(jsonPath("$.items[0].custom_override").value(true)).andExpect(jsonPath("$.items[1].cached_rate").value(0.003))
        .andExpect(jsonPath("$.items[0].time_bands").isArray());
    mvc.perform(get(base).cookie(cookie).param("provider",provider).param("size","10").param("page","1"))
        .andExpect(jsonPath("$.items[0].model").value("model-10"));
    mvc.perform(get(base).cookie(cookie).param("provider",provider).param("size","10").param("page","2"))
        .andExpect(jsonPath("$.items.length()").value(6)).andExpect(jsonPath("$.items[5].cached_known").value(false));
    mvc.perform(get(base).cookie(cookie).param("provider",provider).param("q","MODEL-0"))
        .andExpect(jsonPath("$.total").value(10));
    mvc.perform(get(base).cookie(cookie).param("provider",provider).param("q","%"))
        .andExpect(jsonPath("$.total").value(0));
    mvc.perform(get(base).cookie(cookie).param("size","101")).andExpect(status().isBadRequest());
    mvc.perform(get(base).cookie(cookie).param("page","-1")).andExpect(status().isBadRequest());
    mvc.perform(get(base)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/projects/"+UUID.randomUUID()+"/public-prices").cookie(cookie)).andExpect(status().isNotFound());
  }
  @Test void removedModelsFutureDatesAndFailedRefreshKeepHistorySafe() {
    sync.apply(feed("2",t,"[]"),t);
    assertNull(ModelPricing.quote(store,project,data(),t.minusSeconds(1)));
    var next=(ObjectNode)feed("3",t.plusSeconds(3600),"[]");
    sync.apply(next,t.plusSeconds(1));
    cost("0.00235",t.plusSeconds(60));cost("0.00305",t.plusSeconds(3600));
    assertThrows(Exception.class,()->sync.apply(feed("9",t,"[]"),t.plusSeconds(2)));
    cost("0.00235",t);
    new ModelPriceSync(store,tx,true,true,"http://not-allowed.example").sync();
    assertEquals("failed",store.db.queryForObject("SELECT status FROM model_price_sync WHERE id=1",String.class));
    cost("0.00235",t);
    var replacement=(ObjectNode)feed("4",t.plusSeconds(7200),"[]");
    ((ObjectNode)replacement.path("prices").get(0)).put("model","replacement");
    sync.apply(replacement,t.plusSeconds(7200));
    assertNull(ModelPricing.quote(store,project,data(),t.plusSeconds(7200)));
    cost("0.00235",t); // Tombstones never delete the past version.
  }
}
