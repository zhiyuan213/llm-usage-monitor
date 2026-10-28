package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The actual published scenario payloads must satisfy the production protocol. */
class PublishedScenarioTest {
  JsonNode examples() throws Exception {
    try(var input=getClass().getResourceAsStream("/published-scenarios.json")) {
      assertNotNull(input);
      String raw=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
          .replace("@trace@","0123456789abcdef0123456789abcdef")
          .replace("@time@",Instant.now().toString()).replace("@score@",UUID.randomUUID().toString());
      return Support.parse(raw);
    }
  }
  ObjectNode body(JsonNode events) {
    ObjectNode body=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    var output=body.putArray("events");
    for(JsonNode raw:events) {
      var event=(ObjectNode)raw.deepCopy();
      event.put("eventId",UUID.randomUUID().toString());event.put("revision",1);output.add(event);
    }
    return (ObjectNode)Protocol.defaults(body);
  }
  @Test void everyPublishedRestAndSdkScenarioPassesTheActualValidator() throws Exception {
    for(JsonNode scenario:examples())for(JsonNode events:scenario) {
      var request=body(events);
      assertDoesNotThrow(()->Protocol.validateEvents(request));
      for(JsonNode event:request.path("events"))if(event.path("data").has("usage"))
        assertEquals(0,event.path("data").path("usage").path("cachedInputTokens").asInt(-1));
    }
  }
  @Test void missingCachedUsageWouldFailEvenIfTheOtherTwoFieldsArePresent() throws Exception {
    var request=body(examples().path("single").get(0));
    ((ObjectNode)request.path("events").get(1).path("data").path("usage")).remove("cachedInputTokens");
    var error=assertThrows(Protocol.InvalidEvents.class,()->Protocol.validateEvents(request));
    assertEquals("INVALID_USAGE",error.code);
  }
}
