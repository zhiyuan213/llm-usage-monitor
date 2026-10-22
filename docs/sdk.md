# Source SDKs and REST examples

Canonical SDK repository: [zhiyuan213/llm-usage-monitor-sdk](https://github.com/zhiyuan213/llm-usage-monitor-sdk), with [Node.js / TypeScript](https://github.com/zhiyuan213/llm-usage-monitor-sdk/tree/main/node), [Python](https://github.com/zhiyuan213/llm-usage-monitor-sdk/tree/main/python), and [Java](https://github.com/zhiyuan213/llm-usage-monitor-sdk/tree/main/java) packages. This application repository retains source copies for its smoke scripts and tests.

Clone the SDK repository into your application directory before following the Node.js or Java installation steps:

```sh
git clone https://github.com/zhiyuan213/llm-usage-monitor-sdk.git
```

The SDKs are source packages, version 0.2.0, and are not published to npm, PyPI, or Maven Central. Each client has a bounded in-memory queue: 10,000 events or about 16 MiB. `record` does not make network requests. The client flushes at 100 events, about 256 KiB, or 5 seconds, and supports explicit flush and shutdown.

## Node.js and TypeScript

Node.js 20+; Node.js 24 is used in CI. Install the source package from your application directory:

```sh
npm install ./llm-usage-monitor-sdk/node
```

```js
import { MeterTrail } from '@metertrail/sdk';
const client = new MeterTrail({
  serviceName: 'my-service',
  apiKey: process.env.METERTRAIL_API_KEY,
  endpoint: process.env.METERTRAIL_ENDPOINT,
  onResult: result => console.log(result),
  onError: error => console.error(error.code)
});
client.record({type:'trace',objectId:'request-1',traceId:'0123456789abcdef0123456789abcdef',
  data:{name:'request',status:'success',durationMs:100}});
await client.close(10000);
```

`record` creates an event ID/revision/current timestamp only when absent. It clones input at enqueue time; later application mutations cannot alter a retry. Preserve the original event identity yourself when recording updates. `.paused` exposes quota/key pauses; `.checkQuota()` reads quota state; `.resume()` is explicit.

## Python

Python 3.10+, no runtime dependencies:

```sh
pip install "git+https://github.com/zhiyuan213/llm-usage-monitor-sdk.git@main#subdirectory=python"
```

```python
from metertrail import MeterTrail
with MeterTrail('my-service', on_error=lambda error: print(error['code'])) as client:
    client.record({'type':'trace','objectId':'request-1',
                   'traceId':'0123456789abcdef0123456789abcdef',
                   'data':{'name':'request','status':'success','durationMs':100}})
```

Environment variables supply endpoint/key. A daemon thread runs the sender. `flush(timeout=30)`, `close(timeout=10)`, `resume()`, and `check_quota()` are provided. `paused` is explicit and distinct from network failure.

## Java

Java 17+, Jackson; build/install from source:

```sh
mvn -f llm-usage-monitor-sdk/java/pom.xml install
```

Use the locally installed `com.metertrail:metertrail-sdk:0.2.0` dependency. It is not a public repository coordinate.

```java
try (var client = new com.metertrail.sdk.MeterTrail(
    System.getenv("METERTRAIL_ENDPOINT"), System.getenv("METERTRAIL_API_KEY"),
    "my-service", result -> System.out.println(result), System.err::println)) {
    client.record(new com.fasterxml.jackson.databind.ObjectMapper().readTree(eventJson));
}
```

`record(JsonNode)` queues a copy. A daemon executor sends data. `flush(Duration)`, `pausedReason()`, and `resume()` expose lifecycle control. Shutdown flush is limited to 10 seconds.

## Failure handling

- All SDKs retain exact identities when retrying network errors, 5xx, and temporary throttling; retries are bounded and jittered.
- 401/403 and full-batch quota errors pause ingestion. A callback reports overflow instead of blocking application requests.
- HTTP 200 with status pending is the upload receipt. onResult/on_result receives this receipt and sending ends without automatic polling. For rolling upgrades, SDKs also recognize legacy HTTP 202 receipts.
- Query processing results only when needed: Node getBatch(batchId), Python get_batch(batch_id), Java getBatch(batchId). Each method makes an authenticated query using the current project key and returns the HTTP status and response body. It does not poll in a loop or automatically resend rejected events. Callers decide how to handle final per-event results.
- 413 causes splitting; single-event failures are reported. No blanket retry of permanent field errors.
- SDK queues are memory-only. Process crashes may lose unsent data. Graceful shutdown may leave events pending when the deadline expires. Use an application-owned durable spool when crash recovery is required.
- The seven REST examples demonstrate request construction, not the complete SDK retry engine. Production direct REST callers must implement the documented retry/result handling.

Python/Node/Java SDKs have no model-provider dependency: extract actual `usage` from your provider response, then record an Observation. They do not proxy the model call or automatically create a Trace or Score.

## Optional result query

```js
const { status, data } = await client.getBatch(batchId);
```

```python
status, data = client.get_batch(batch_id)
```

```java
var result = client.getBatch(batchId);
// result.status(), result.data()
```

A successful POST returns HTTP 200 and a fixed status pending receipt. For live status use GET https://metertrail.com/v1/batches/{batchId} with the same project API key, or the SDK method above. These methods return one result, without automatic repeated polling. SDK receipt callbacks no longer mean final processing completed; applications that relied on final callbacks must query explicitly.
