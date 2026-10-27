# SDK 接入示例与 ID 规则

本文与英文版使用完全相同的代码和参数，只翻译说明。Node.js、Python、Java 各提供单次模型调用、多次模型调用和两种评分目标的四份完整代码。

## ID 规则

| 字段 | 规则与使用方式 |
| --- | --- |
| eventId | 每条新事件在同一项目内唯一，恰好 36 个 Unicode 字符，不限制 UUID 格式。推荐 UUID；SDK 未传时自动生成。重试同一事件保留原值和原内容，不重新生成。常规 UUID 大小写视为同一事件；其他字符串区分大小写。 |
| objectId | 非空字符串，最多 128 个 UTF-8 字节（不是 128 个汉字）。唯一范围是同一项目、同一 type。它代表一条具体请求、步骤或评分，不是业务功能名称。新对象换 ID；更新同一对象保留 ID 并提高 revision。 |
| traceId | Trace、Observation 必填，恰好 32 个十六进制字符，仅 0–9、a–f、A–F；建议统一小写。一笔业务请求生成一次，所属 Trace 与所有 Observation 共用；新业务请求换值。它负责关联调用链，不能代替 objectId。 |
| spanId | Observation 必填，恰好 16 个十六进制字符。每个步骤分配不同值；同一步骤重试保留。 |
| parentSpanId | 可省略或为 null；有父步骤时填父 Observation 的 spanId，恰好 16 个十六进制字符，不是父 objectId。 |
| targetType | Score 的目标类型，只能是 trace 或 observation。 |
| targetId | Score 必填，最多 128 个 UTF-8 字节；填同一项目中目标 Trace 或 Observation 的 objectId，由 targetType 决定。不是 eventId、traceId 或 spanId。 |
| revision | 可省略，默认 1。只上报最终结果时保持 1；确实修改已存在对象才递增。版本号与请求是否结束无关。 |

SDK 自动生成 eventId，不会替你生成 objectId、traceId、spanId 或选择评分目标。保留待重试事件；重新调用 record 且省略 eventId 会生成新事件身份。

## 两种评分目标

你说的 scope 应为 score（评分）。它可以评价整次请求，也可以只评价某个模型步骤，按业务选择。

下列两段是替代方案：前者评价整次请求，后者评价第一步。示例 ID 一致，便于看清关联关系；实际请使用已成功处理的目标 objectId。Score 自己也有独立的 objectId。

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

## 运行说明

按源码 SDK 仓库说明安装 SDK；在服务端设置 METERTRAIL_ENDPOINT=https://metertrail.com 和 METERTRAIL_API_KEY。Node/Python 从 SDK 仓库根目录运行；Java 将 SDK 与 Jackson 加入 classpath。评分示例先设置 METERTRAIL_TRACE_ID 为之前打印的 traceId，评价步骤的示例指向第一条 Observation，评价整次请求的示例指向 Trace；评价整次请求时按上面的目标规则同时修改 targetType、targetId。示例中的 now 使用运行时当前时间，耗时和 Token 是演示值，请替换成实际值。

## Node.js

### 一次模型调用：一起发送 Trace + Observation

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
          "outputTokens": 400
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

### 多次模型调用：分组发送，共用 traceId

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
          "outputTokens": 500
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
          "outputTokens": 200
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
          "outputTokens": 500
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

### 评价整次请求：指向 Trace 的 objectId

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

### 可选评分：单独发送，指向第一条 Observation

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

### 一次模型调用：一起发送 Trace + Observation

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
          "outputTokens": 400
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

### 多次模型调用：分组发送，共用 traceId

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
          "outputTokens": 500
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
          "outputTokens": 200
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
          "outputTokens": 500
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

### 评价整次请求：指向 Trace 的 objectId

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

### 可选评分：单独发送，指向第一条 Observation

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

### 一次模型调用：一起发送 Trace + Observation

```java
import com.metertrail.sdk.MeterTrail;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class Scenario {
  public static void main(String[] args) throws Exception {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    if (traceId == null || !traceId.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Use the earlier request traceId in METERTRAIL_TRACE_ID");
    System.out.println("traceId: " + traceId); // Keep for a later score.
    // Use actual request/step start times and measured durations in your app.
    String payload = """
[
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
          "outputTokens": 400
        }
      }
    }
  ]
]
      """.replace("@trace@", traceId).replace("@time@", Instant.now().toString())
        .replace("@score@", UUID.randomUUID().toString());
    var batches = new ObjectMapper().readTree(payload);
    try (var sdk = new MeterTrail(System.getenv("METERTRAIL_ENDPOINT"),
        System.getenv("METERTRAIL_API_KEY"), "document-api", System.out::println, System.err::println)) {
      for (var events : batches) {
        for (var event : events)
          if (!sdk.record(event)) throw new IllegalStateException("SDK buffer rejected event");
        if (!sdk.flush(Duration.ofSeconds(30)))
          throw new IllegalStateException("SDK could not finish sending; retain buffered events");
      }
    }
  }
}
```

### 多次模型调用：分组发送，共用 traceId

```java
import com.metertrail.sdk.MeterTrail;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class Scenario {
  public static void main(String[] args) throws Exception {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    if (traceId == null || !traceId.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Use the earlier request traceId in METERTRAIL_TRACE_ID");
    System.out.println("traceId: " + traceId); // Keep for a later score.
    // Use actual request/step start times and measured durations in your app.
    String payload = """
[
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
          "outputTokens": 500
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
          "outputTokens": 200
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
          "outputTokens": 500
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
]
      """.replace("@trace@", traceId).replace("@time@", Instant.now().toString())
        .replace("@score@", UUID.randomUUID().toString());
    var batches = new ObjectMapper().readTree(payload);
    try (var sdk = new MeterTrail(System.getenv("METERTRAIL_ENDPOINT"),
        System.getenv("METERTRAIL_API_KEY"), "document-api", System.out::println, System.err::println)) {
      for (var events : batches) {
        for (var event : events)
          if (!sdk.record(event)) throw new IllegalStateException("SDK buffer rejected event");
        if (!sdk.flush(Duration.ofSeconds(30)))
          throw new IllegalStateException("SDK could not finish sending; retain buffered events");
      }
    }
  }
}
```

### 评价整次请求：指向 Trace 的 objectId

```java
import com.metertrail.sdk.MeterTrail;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class Scenario {
  public static void main(String[] args) throws Exception {
    String traceId = System.getenv("METERTRAIL_TRACE_ID");
    if (traceId == null || !traceId.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Use the earlier request traceId in METERTRAIL_TRACE_ID");
    System.out.println("traceId: " + traceId); // Keep for a later score.
    // Use actual request/step start times and measured durations in your app.
    String payload = """
[
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
]
      """.replace("@trace@", traceId).replace("@time@", Instant.now().toString())
        .replace("@score@", UUID.randomUUID().toString());
    var batches = new ObjectMapper().readTree(payload);
    try (var sdk = new MeterTrail(System.getenv("METERTRAIL_ENDPOINT"),
        System.getenv("METERTRAIL_API_KEY"), "document-api", System.out::println, System.err::println)) {
      for (var events : batches) {
        for (var event : events)
          if (!sdk.record(event)) throw new IllegalStateException("SDK buffer rejected event");
        if (!sdk.flush(Duration.ofSeconds(30)))
          throw new IllegalStateException("SDK could not finish sending; retain buffered events");
      }
    }
  }
}
```

### 可选评分：单独发送，指向第一条 Observation

```java
import com.metertrail.sdk.MeterTrail;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class Scenario {
  public static void main(String[] args) throws Exception {
    String traceId = System.getenv("METERTRAIL_TRACE_ID");
    if (traceId == null || !traceId.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Use the earlier request traceId in METERTRAIL_TRACE_ID");
    System.out.println("traceId: " + traceId); // Keep for a later score.
    // Use actual request/step start times and measured durations in your app.
    String payload = """
[
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
]
      """.replace("@trace@", traceId).replace("@time@", Instant.now().toString())
        .replace("@score@", UUID.randomUUID().toString());
    var batches = new ObjectMapper().readTree(payload);
    try (var sdk = new MeterTrail(System.getenv("METERTRAIL_ENDPOINT"),
        System.getenv("METERTRAIL_API_KEY"), "document-api", System.out::println, System.err::println)) {
      for (var events : batches) {
        for (var event : events)
          if (!sdk.record(event)) throw new IllegalStateException("SDK buffer rejected event");
        if (!sdk.flush(Duration.ofSeconds(30)))
          throw new IllegalStateException("SDK could not finish sending; retain buffered events");
      }
    }
  }
}
```

