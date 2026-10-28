# SDK examples and ID rules

This guide uses exactly the same code and parameters as the Chinese version; only explanatory text is translated. Each Node.js, Python and Java section includes complete examples for one model call, multiple model calls and both score targets.

## ID rules

| Field | Rule and usage |
| --- | --- |
| eventId | Unique per new event within the same project; exactly 36 Unicode characters, with no UUID format requirement. UUIDs are recommended; SDKs generate one when omitted. Retrying an event preserves its original ID and content. Conventional UUID spellings are case-insensitive; other strings are case-sensitive. |
| objectId | A nonblank string of at most 128 UTF-8 bytes, unique within project + type. Identifies a particular request, step or score, not a business feature. New objects use new IDs; updates retain the ID and increase revision. |
| traceId | Required for Trace and Observation; exactly 32 hexadecimal characters (0–9, a–f, A–F). Use lowercase consistently. Generate once per application request and share across its Trace and Observations; use a new value for a new request. This groups the trace and does not replace objectId. |
| spanId | Required for Observation; exactly 16 hexadecimal characters. Assign distinct values to distinct steps; preserve on retry. |
| parentSpanId | Optional or null; otherwise the parent Observation's spanId, exactly 16 hexadecimal characters, not its objectId. |
| targetType | Score target type: trace or observation. |
| targetId | Required for Score; at most 128 UTF-8 bytes. The target Trace or Observation's objectId in the same project, selected by targetType. Never its eventId, traceId or spanId. |
| revision | Optional; defaults to 1. Keep 1 when reporting final results only; increase only to update an existing object. It does not indicate whether a request has completed. |

SDKs generate eventId, but do not generate objectId, traceId or spanId, or select the score target for you. Retain events for retries; calling record again without eventId generates a new event identity.

## Two score targets

The event type is score, not scope. A score may evaluate the whole request or one model step, according to your application.

These are alternatives: the first evaluates the whole request, the second its first step. IDs match across examples to make the relationships clear; use the objectId of a successfully processed target in practice. Each Score has its own independent objectId.

```json
{
  "type": "score",
  "objectId": "score-feedback-1",
  "data": {
    "name": "User satisfaction",
    "targetType": "trace",
    "targetId": "trace-0123456789abcdef0123456789abcdef",
    "valueType": "numeric",
    "value": 1
  }
}
```

```json
{
  "type": "score",
  "objectId": "score-feedback-2",
  "data": {
    "name": "User satisfaction",
    "targetType": "observation",
    "targetId": "obs-0123456789abcdef0123456789abcdef-1",
    "valueType": "numeric",
    "value": 1
  }
}
```

## Running the examples

Install the source SDK as described in its repository. Set METERTRAIL_ENDPOINT=https://metertrail.com and METERTRAIL_API_KEY on the server. Run Node/Python from the SDK repository root; add the SDK and Jackson to the Java classpath. For scores, set METERTRAIL_TRACE_ID to the earlier printed traceId. The step-score code targets the first Observation; the whole-request score targets the Trace; to evaluate the whole request, change both targetType and targetId using the rules above. Timestamps use the runtime current time; durations and tokens are illustrative and must be replaced with measured values.

## Node.js

### One model call: Trace + Observation together

```javascript
import { randomUUID } from 'node:crypto';
import { MeterTrail } from './node/index.mjs';

// Run from the source SDK repository root. Set METERTRAIL_ENDPOINT and METERTRAIL_API_KEY.
const traceId = randomUUID().replaceAll('-', '');
if (!/^[0-9a-f]{32}$/.test(traceId || '')) throw Error('Set METERTRAIL_TRACE_ID to the earlier request traceId');
console.log('traceId:', traceId); // Keep this ID if a score will be sent later.
const startedAt = new Date().toISOString(); // Use actual request/step start times in your app.
const batches = [
  [
    {
      "type": "trace",
      "objectId": "trace-@trace@",
      "traceId": "@trace@",
      "startedAt": "@time@",
      "data": {
        "name": "Document analysis",
        "status": "success",
        "durationMs": 2100,
        "userId": "demo-user",
        "customerId": "demo-customer",
        "feature": "document-analysis"
      }
    },
    {
      "type": "observation",
      "objectId": "obs-@trace@-1",
      "traceId": "@trace@",
      "spanId": "0000000000000001",
      "startedAt": "@time@",
      "data": {
        "observationType": "generation",
        "status": "success",
        "durationMs": 2000,
        "provider": "example",
        "model": "example-model",
        "usage": {
          "inputTokens": 1500,
          "outputTokens": 400,
          "cachedInputTokens": 0
        }
      }
    }
  ]
].map(events => JSON.parse(JSON.stringify(events)
  .replaceAll('@trace@', traceId).replaceAll('@time@', startedAt).replaceAll('@score@', randomUUID())));
const sdk = new MeterTrail({ serviceName:'document-api', onResult:console.log, onError:console.error });
try {
  for (const events of batches) {
    // Each group represents a completed step or request; no prompt/answer text is sent.
    for (const event of events) if (!sdk.record(event)) throw Error('SDK buffer rejected event');
    await sdk.flush(); // Send this group before recording the next group.
    if (sdk.paused) throw Error('SDK paused: ' + sdk.paused);
  }
} finally { console.log(await sdk.close()); }
```

### Multiple calls: separate groups sharing traceId

```javascript
import { randomUUID } from 'node:crypto';
import { MeterTrail } from './node/index.mjs';

// Run from the source SDK repository root. Set METERTRAIL_ENDPOINT and METERTRAIL_API_KEY.
const traceId = randomUUID().replaceAll('-', '');
if (!/^[0-9a-f]{32}$/.test(traceId || '')) throw Error('Set METERTRAIL_TRACE_ID to the earlier request traceId');
console.log('traceId:', traceId); // Keep this ID if a score will be sent later.
const startedAt = new Date().toISOString(); // Use actual request/step start times in your app.
const batches = [
  [
    {
      "type": "observation",
      "objectId": "obs-@trace@-1",
      "traceId": "@trace@",
      "spanId": "0000000000000001",
      "startedAt": "@time@",
      "data": {
        "observationType": "generation",
        "status": "success",
        "durationMs": 2000,
        "provider": "example",
        "model": "example-model",
        "usage": {
          "inputTokens": 2500,
          "outputTokens": 500,
          "cachedInputTokens": 0
        }
      }
    }
  ],
  [
    {
      "type": "observation",
      "objectId": "obs-@trace@-2",
      "traceId": "@trace@",
      "spanId": "0000000000000002",
      "startedAt": "@time@",
      "data": {
        "observationType": "generation",
        "status": "success",
        "durationMs": 1000,
        "provider": "example",
        "model": "example-model",
        "usage": {
          "inputTokens": 1000,
          "outputTokens": 200,
          "cachedInputTokens": 0
        }
      }
    }
  ],
  [
    {
      "type": "observation",
      "objectId": "obs-@trace@-3",
      "traceId": "@trace@",
      "spanId": "0000000000000003",
      "startedAt": "@time@",
      "data": {
        "observationType": "generation",
        "status": "success",
        "durationMs": 3000,
        "provider": "example",
        "model": "example-model",
        "usage": {
          "inputTokens": 2000,
          "outputTokens": 500,
          "cachedInputTokens": 0
        }
      }
    },
    {
      "type": "trace",
      "objectId": "trace-@trace@",
      "traceId": "@trace@",
      "startedAt": "@time@",
      "data": {
        "name": "Document analysis",
        "status": "success",
        "durationMs": 6200,
        "userId": "demo-user",
        "customerId": "demo-customer",
        "feature": "document-analysis"
      }
    }
  ]
].map(events => JSON.parse(JSON.stringify(events)
  .replaceAll('@trace@', traceId).replaceAll('@time@', startedAt).replaceAll('@score@', randomUUID())));
const sdk = new MeterTrail({ serviceName:'document-api', onResult:console.log, onError:console.error });
try {
  for (const events of batches) {
    // Each group represents a completed step or request; no prompt/answer text is sent.
    for (const event of events) if (!sdk.record(event)) throw Error('SDK buffer rejected event');
    await sdk.flush(); // Send this group before recording the next group.
    if (sdk.paused) throw Error('SDK paused: ' + sdk.paused);
  }
} finally { console.log(await sdk.close()); }
```

### Score the whole request: target the Trace objectId

```javascript
import { randomUUID } from 'node:crypto';
import { MeterTrail } from './node/index.mjs';

// Run from the source SDK repository root. Set METERTRAIL_ENDPOINT and METERTRAIL_API_KEY.
const traceId = process.env.METERTRAIL_TRACE_ID;
if (!/^[0-9a-f]{32}$/.test(traceId || '')) throw Error('Set METERTRAIL_TRACE_ID to the earlier request traceId');
console.log('traceId:', traceId); // Keep this ID if a score will be sent later.
const startedAt = new Date().toISOString(); // Use actual request/step start times in your app.
const batches = [
  [
    {
      "type": "score",
      "objectId": "score-@score@",
      "startedAt": "@time@",
      "data": {
        "name": "User satisfaction",
        "targetType": "trace",
        "targetId": "trace-@trace@",
        "valueType": "numeric",
        "value": 1
      }
    }
  ]
].map(events => JSON.parse(JSON.stringify(events)
  .replaceAll('@trace@', traceId).replaceAll('@time@', startedAt).replaceAll('@score@', randomUUID())));
const sdk = new MeterTrail({ serviceName:'document-api', onResult:console.log, onError:console.error });
try {
  for (const events of batches) {
    // Each group represents a completed step or request; no prompt/answer text is sent.
    for (const event of events) if (!sdk.record(event)) throw Error('SDK buffer rejected event');
    await sdk.flush(); // Send this group before recording the next group.
    if (sdk.paused) throw Error('SDK paused: ' + sdk.paused);
  }
} finally { console.log(await sdk.close()); }
```

### Optional score: sent separately, targeting the first Observation

```javascript
import { randomUUID } from 'node:crypto';
import { MeterTrail } from './node/index.mjs';

// Run from the source SDK repository root. Set METERTRAIL_ENDPOINT and METERTRAIL_API_KEY.
const traceId = process.env.METERTRAIL_TRACE_ID;
if (!/^[0-9a-f]{32}$/.test(traceId || '')) throw Error('Set METERTRAIL_TRACE_ID to the earlier request traceId');
console.log('traceId:', traceId); // Keep this ID if a score will be sent later.
const startedAt = new Date().toISOString(); // Use actual request/step start times in your app.
const batches = [
  [
    {
      "type": "score",
      "objectId": "score-@score@",
      "startedAt": "@time@",
      "data": {
        "name": "User satisfaction",
        "targetType": "observation",
        "targetId": "obs-@trace@-1",
        "valueType": "numeric",
        "value": 1
      }
    }
  ]
].map(events => JSON.parse(JSON.stringify(events)
  .replaceAll('@trace@', traceId).replaceAll('@time@', startedAt).replaceAll('@score@', randomUUID())));
const sdk = new MeterTrail({ serviceName:'document-api', onResult:console.log, onError:console.error });
try {
  for (const events of batches) {
    // Each group represents a completed step or request; no prompt/answer text is sent.
    for (const event of events) if (!sdk.record(event)) throw Error('SDK buffer rejected event');
    await sdk.flush(); // Send this group before recording the next group.
    if (sdk.paused) throw Error('SDK paused: ' + sdk.paused);
  }
} finally { console.log(await sdk.close()); }
```

## Python

### One model call: Trace + Observation together

```python
import json
import os
import uuid
from datetime import datetime, timezone
from python.metertrail import MeterTrail

# Run from the source SDK repository root. Set METERTRAIL_ENDPOINT and METERTRAIL_API_KEY.
trace_id = uuid.uuid4().hex
if len(trace_id) != 32 or any(c not in '0123456789abcdef' for c in trace_id):
    raise ValueError('Use the earlier request traceId')
print('traceId:', trace_id)  # Keep this ID for a later score.
started_at = datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')
# In your app, use actual request/step start times and measured durations.
batches = json.loads(r'''[
  [
    {
      "type": "trace",
      "objectId": "trace-@trace@",
      "traceId": "@trace@",
      "startedAt": "@time@",
      "data": {
        "name": "Document analysis",
        "status": "success",
        "durationMs": 2100,
        "userId": "demo-user",
        "customerId": "demo-customer",
        "feature": "document-analysis"
      }
    },
    {
      "type": "observation",
      "objectId": "obs-@trace@-1",
      "traceId": "@trace@",
      "spanId": "0000000000000001",
      "startedAt": "@time@",
      "data": {
        "observationType": "generation",
        "status": "success",
        "durationMs": 2000,
        "provider": "example",
        "model": "example-model",
        "usage": {
          "inputTokens": 1500,
          "outputTokens": 400,
          "cachedInputTokens": 0
        }
      }
    }
  ]
]'''.replace('@trace@', trace_id)
    .replace('@time@', started_at).replace('@score@', str(uuid.uuid4())))
sdk = MeterTrail(service_name='document-api', on_result=print, on_error=print)
try:
    for events in batches:
        for event in events:
            if not sdk.record(event):
                raise RuntimeError('SDK buffer rejected event')
        if not sdk.flush():
            raise RuntimeError('SDK could not finish sending; retain buffered events')
finally:
    print(sdk.close())
```

### Multiple calls: separate groups sharing traceId

```python
import json
import os
import uuid
from datetime import datetime, timezone
from python.metertrail import MeterTrail

# Run from the source SDK repository root. Set METERTRAIL_ENDPOINT and METERTRAIL_API_KEY.
trace_id = uuid.uuid4().hex
if len(trace_id) != 32 or any(c not in '0123456789abcdef' for c in trace_id):
    raise ValueError('Use the earlier request traceId')
print('traceId:', trace_id)  # Keep this ID for a later score.
started_at = datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')
# In your app, use actual request/step start times and measured durations.
batches = json.loads(r'''[
  [
    {
      "type": "observation",
      "objectId": "obs-@trace@-1",
      "traceId": "@trace@",
      "spanId": "0000000000000001",
      "startedAt": "@time@",
      "data": {
        "observationType": "generation",
        "status": "success",
        "durationMs": 2000,
        "provider": "example",
        "model": "example-model",
        "usage": {
          "inputTokens": 2500,
          "outputTokens": 500,
          "cachedInputTokens": 0
        }
      }
    }
  ],
  [
    {
      "type": "observation",
      "objectId": "obs-@trace@-2",
      "traceId": "@trace@",
      "spanId": "0000000000000002",
      "startedAt": "@time@",
      "data": {
        "observationType": "generation",
        "status": "success",
        "durationMs": 1000,
        "provider": "example",
        "model": "example-model",
        "usage": {
          "inputTokens": 1000,
          "outputTokens": 200,
          "cachedInputTokens": 0
        }
      }
    }
  ],
  [
    {
      "type": "observation",
      "objectId": "obs-@trace@-3",
      "traceId": "@trace@",
      "spanId": "0000000000000003",
      "startedAt": "@time@",
      "data": {
        "observationType": "generation",
        "status": "success",
        "durationMs": 3000,
        "provider": "example",
        "model": "example-model",
        "usage": {
          "inputTokens": 2000,
          "outputTokens": 500,
          "cachedInputTokens": 0
        }
      }
    },
    {
      "type": "trace",
      "objectId": "trace-@trace@",
      "traceId": "@trace@",
      "startedAt": "@time@",
      "data": {
        "name": "Document analysis",
        "status": "success",
        "durationMs": 6200,
        "userId": "demo-user",
        "customerId": "demo-customer",
        "feature": "document-analysis"
      }
    }
  ]
]'''.replace('@trace@', trace_id)
    .replace('@time@', started_at).replace('@score@', str(uuid.uuid4())))
sdk = MeterTrail(service_name='document-api', on_result=print, on_error=print)
try:
    for events in batches:
        for event in events:
            if not sdk.record(event):
                raise RuntimeError('SDK buffer rejected event')
        if not sdk.flush():
            raise RuntimeError('SDK could not finish sending; retain buffered events')
finally:
    print(sdk.close())
```

### Score the whole request: target the Trace objectId

```python
import json
import os
import uuid
from datetime import datetime, timezone
from python.metertrail import MeterTrail

# Run from the source SDK repository root. Set METERTRAIL_ENDPOINT and METERTRAIL_API_KEY.
trace_id = os.environ['METERTRAIL_TRACE_ID']
if len(trace_id) != 32 or any(c not in '0123456789abcdef' for c in trace_id):
    raise ValueError('Use the earlier request traceId')
print('traceId:', trace_id)  # Keep this ID for a later score.
started_at = datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')
# In your app, use actual request/step start times and measured durations.
batches = json.loads(r'''[
  [
    {
      "type": "score",
      "objectId": "score-@score@",
      "startedAt": "@time@",
      "data": {
        "name": "User satisfaction",
        "targetType": "trace",
        "targetId": "trace-@trace@",
        "valueType": "numeric",
        "value": 1
      }
    }
  ]
]'''.replace('@trace@', trace_id)
    .replace('@time@', started_at).replace('@score@', str(uuid.uuid4())))
sdk = MeterTrail(service_name='document-api', on_result=print, on_error=print)
try:
    for events in batches:
        for event in events:
            if not sdk.record(event):
                raise RuntimeError('SDK buffer rejected event')
        if not sdk.flush():
            raise RuntimeError('SDK could not finish sending; retain buffered events')
finally:
    print(sdk.close())
```

### Optional score: sent separately, targeting the first Observation

```python
import json
import os
import uuid
from datetime import datetime, timezone
from python.metertrail import MeterTrail

# Run from the source SDK repository root. Set METERTRAIL_ENDPOINT and METERTRAIL_API_KEY.
trace_id = os.environ['METERTRAIL_TRACE_ID']
if len(trace_id) != 32 or any(c not in '0123456789abcdef' for c in trace_id):
    raise ValueError('Use the earlier request traceId')
print('traceId:', trace_id)  # Keep this ID for a later score.
started_at = datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')
# In your app, use actual request/step start times and measured durations.
batches = json.loads(r'''[
  [
    {
      "type": "score",
      "objectId": "score-@score@",
      "startedAt": "@time@",
      "data": {
        "name": "User satisfaction",
        "targetType": "observation",
        "targetId": "obs-@trace@-1",
        "valueType": "numeric",
        "value": 1
      }
    }
  ]
]'''.replace('@trace@', trace_id)
    .replace('@time@', started_at).replace('@score@', str(uuid.uuid4())))
sdk = MeterTrail(service_name='document-api', on_result=print, on_error=print)
try:
    for events in batches:
        for event in events:
            if not sdk.record(event):
                raise RuntimeError('SDK buffer rejected event')
        if not sdk.flush():
            raise RuntimeError('SDK could not finish sending; retain buffered events')
finally:
    print(sdk.close())
```

## Java

### One model call: Trace + Observation together

```java
import com.metertrail.sdk.MeterTrail;
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
    String traceId = UUID.randomUUID().toString().replace("-", "");
    if (traceId == null || !traceId.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Use the earlier request traceId in METERTRAIL_TRACE_ID");
    System.out.println("traceId: " + traceId); // Keep this ID for later feedback.
    String startedAt = Instant.now().toString(); // Use actual request/step start times.
    List<List<Event<?>>> batches = List.of(
      List.<Event<?>>of(
        new Event<>("trace", "trace-" + traceId + "", traceId, null, startedAt,
            new TraceData("Document analysis", "success", 2100L, "demo-user", "demo-customer", "document-analysis")),
        new Event<>("observation", "obs-" + traceId + "-1", traceId, "0000000000000001", startedAt,
            new ObservationData("generation", "success", 2000L, "example", "example-model", new Usage(1500L, 400L, 0L)))
      )
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
```

### Multiple calls: separate groups sharing traceId

```java
import com.metertrail.sdk.MeterTrail;
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
    String traceId = UUID.randomUUID().toString().replace("-", "");
    if (traceId == null || !traceId.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Use the earlier request traceId in METERTRAIL_TRACE_ID");
    System.out.println("traceId: " + traceId); // Keep this ID for later feedback.
    String startedAt = Instant.now().toString(); // Use actual request/step start times.
    List<List<Event<?>>> batches = List.of(
      List.<Event<?>>of(
        new Event<>("observation", "obs-" + traceId + "-1", traceId, "0000000000000001", startedAt,
            new ObservationData("generation", "success", 2000L, "example", "example-model", new Usage(2500L, 500L, 0L)))
      ),
      List.<Event<?>>of(
        new Event<>("observation", "obs-" + traceId + "-2", traceId, "0000000000000002", startedAt,
            new ObservationData("generation", "success", 1000L, "example", "example-model", new Usage(1000L, 200L, 0L)))
      ),
      List.<Event<?>>of(
        new Event<>("observation", "obs-" + traceId + "-3", traceId, "0000000000000003", startedAt,
            new ObservationData("generation", "success", 3000L, "example", "example-model", new Usage(2000L, 500L, 0L))),
        new Event<>("trace", "trace-" + traceId + "", traceId, null, startedAt,
            new TraceData("Document analysis", "success", 6200L, "demo-user", "demo-customer", "document-analysis"))
      )
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
```

### Score the whole request: target the Trace objectId

```java
import com.metertrail.sdk.MeterTrail;
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
    String traceId = System.getenv("METERTRAIL_TRACE_ID");
    if (traceId == null || !traceId.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Use the earlier request traceId in METERTRAIL_TRACE_ID");
    System.out.println("traceId: " + traceId); // Keep this ID for later feedback.
    String startedAt = Instant.now().toString(); // Use actual request/step start times.
    List<List<Event<?>>> batches = List.of(
      List.<Event<?>>of(
        new Event<>("score", "score-@score@", null, null, startedAt,
            new ScoreData("User satisfaction", "trace", "trace-" + traceId + "", "numeric", 1))
      )
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
```

### Optional score: sent separately, targeting the first Observation

```java
import com.metertrail.sdk.MeterTrail;
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
    String traceId = System.getenv("METERTRAIL_TRACE_ID");
    if (traceId == null || !traceId.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Use the earlier request traceId in METERTRAIL_TRACE_ID");
    System.out.println("traceId: " + traceId); // Keep this ID for later feedback.
    String startedAt = Instant.now().toString(); // Use actual request/step start times.
    List<List<Event<?>>> batches = List.of(
      List.<Event<?>>of(
        new Event<>("score", "score-@score@", null, null, startedAt,
            new ScoreData("User satisfaction", "observation", "obs-" + traceId + "-1", "numeric", 1))
      )
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
```

