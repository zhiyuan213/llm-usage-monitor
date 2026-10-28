// Java examples construct typed objects, then let Jackson serialize them.
const literal=value=>JSON.stringify(value);
function expression(value){
 if(value==null)return 'null';
 if(value==='@score@')return 'UUID.randomUUID().toString()';
 return value.split('@trace@').map(literal).join(' + traceId + ');
}
function data(value){
 if(value.observationType)return `new ObservationData(${literal(value.observationType)}, ${literal(value.status)}, ${value.durationMs}L, ${literal(value.provider)}, ${literal(value.model)}, new Usage(${value.usage.inputTokens}L, ${value.usage.outputTokens}L, ${value.usage.cachedInputTokens}L))`;
 if(value.targetType)return `new ScoreData(${literal(value.name)}, ${literal(value.targetType)}, ${expression(value.targetId)}, ${literal(value.valueType)}, ${value.value})`;
 return `new TraceData(${literal(value.name)}, ${literal(value.status)}, ${value.durationMs}L, ${literal(value.userId)}, ${literal(value.customerId)}, ${literal(value.feature)})`;
}
export function javaScenarioSource(kind,batches){
 const groups=batches.map(events=>'List.<Event<?>>of(\n        '+events.map(e=>`new Event<>(${literal(e.type)}, ${expression(e.objectId)}, ${e.traceId?'traceId':'null'}, ${expression(e.spanId)}, startedAt,\n            ${data(e.data)})`).join(',\n        ')+'\n      )').join(',\n      ');
 return `import com.metertrail.sdk.MeterTrail;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class Scenario {
  public record Event<T>(String type, String objectId, String traceId, String spanId, String startedAt, T data) {}
  public record TraceData(String name, String status, long durationMs, String userId, String customerId, String feature) {}
  public record ObservationData(String observationType, String status, long durationMs, String provider, String model, Usage usage) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Usage(Long inputTokens, Long outputTokens, Long cachedInputTokens) {}
  public record ScoreData(String name, String targetType, String targetId, String valueType, double value) {}

  public static void main(String[] args) throws Exception {
    String traceId = ${kind.startsWith('score')?'System.getenv("METERTRAIL_TRACE_ID")':'UUID.randomUUID().toString().replace("-", "")'};
    if (traceId == null || !traceId.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Use the earlier request traceId in METERTRAIL_TRACE_ID");
    System.out.println("traceId: " + traceId); // Keep this ID for later feedback.
    String startedAt = Instant.now().toString(); // Use actual request/step start times.
    List<List<Event<?>>> batches = List.of(
      ${groups}
    );
    ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    try (var sdk = new MeterTrail(System.getenv("METERTRAIL_ENDPOINT"),
        System.getenv("METERTRAIL_API_KEY"), "document-api", System.out::println, System.err::println)) {
      for (List<Event<?>> events : batches) {
        for (Event<?> event : events)
          if (!sdk.record(mapper.valueToTree(event))) throw new IllegalStateException("SDK buffer rejected event");
        if (!sdk.flush(Duration.ofSeconds(30)))
          throw new IllegalStateException("SDK could not finish sending; retain buffered events");
      }
    }
  }
}
`;
}
