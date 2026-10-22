<script setup lang="ts">
import { t } from "./lib";
const bodyExample =
  '{\n  "schemaVersion": "1.0",\n  "agent": {\n    "serviceName": "demo-api",\n    "environment": "test",\n    "instanceId": "demo-1"\n  },\n  "events": [\n    {\n      "eventId": "550e8400-e29b-41d4-a716-446655440000",\n      "type": "trace",\n      "objectId": "trace-demo-1",\n      "revision": 1,\n      "traceId": "0123456789abcdef0123456789abcdef",\n      "startedAt": "2026-10-23T00:00:00Z",\n      "data": {\n        "name": "Document analysis",\n        "status": "success",\n        "durationMs": 1850,\n        "userId": "demo-user",\n        "customerId": "demo-customer",\n        "feature": "document-analysis"\n      }\n    },\n    {\n      "eventId": "550e8400-e29b-41d4-a716-446655440001",\n      "type": "observation",\n      "objectId": "obs-demo-1",\n      "revision": 1,\n      "traceId": "0123456789abcdef0123456789abcdef",\n      "spanId": "0123456789abcdef",\n      "parentSpanId": null,\n      "startedAt": "2026-10-23T00:00:00Z",\n      "data": {\n        "observationType": "generation",\n        "status": "success",\n        "durationMs": 1850,\n        "provider": "example",\n        "model": "example-model",\n        "usage": {\n          "inputTokens": 1500,\n          "outputTokens": 400,\n          "cachedInputTokens": 500\n        }\n      }\n    },\n    {\n      "eventId": "550e8400-e29b-41d4-a716-446655440002",\n      "type": "score",\n      "objectId": "score-demo-1",\n      "revision": 1,\n      "startedAt": "2026-10-23T00:00:00Z",\n      "data": {\n        "name": "quality",\n        "targetType": "observation",\n        "targetId": "obs-demo-1",\n        "valueType": "numeric",\n        "value": 0.95\n      }\n    }\n  ]\n}';
const receiptExample =
  '{"batchId":"550e8400-e29b-41d4-a716-446655440099","received":3,"status":"pending"}';
const resultExample =
  '{"status":"completed","received":3,"accepted":3,"duplicates":0,"rejected":0,"errors":[]}';
const minimal = JSON.parse(bodyExample);
minimal.events = [minimal.events[0]];
for (const name of ["userId", "customerId", "feature"])
  delete minimal.events[0].data[name];
const minimalBody = JSON.stringify(minimal, null, 2);
</script>
<template>
  <div class="protocol-reference">
    <div class="api-endpoint">
      <p>
        <strong>URL：</strong
        ><code>https://metertrail.com/v1/events/batch</code>
      </p>
      <p><strong>Method：</strong><code>POST</code></p>
    </div>
    <h3>Header</h3>
    <div class="code-box">
      <div>{{ t("Request headers", "请求头示例") }}</div>
      <pre>
Authorization: Bearer lm_live_&lt;YOUR_API_KEY&gt;
Content-Type: application/json</pre>
    </div>
    <h3>Body</h3>
    <p>
      {{
        t(
          "Replace serviceName with your application name and use the current UTC time for startedAt. Generate new IDs for new events; keep the original event unchanged when retrying. revision starts at 1.",
          "将 serviceName 换成应用名称，startedAt 换成当前 UTC 时间。新事件生成新 ID；网络重试时原样重发，revision 首次填 1。",
        )
      }}
    </p>
    <div class="code-box">
      <div>Body · JSON</div>
      <pre>{{ minimalBody }}</pre>
    </div>
    <h3>Resp</h3>
    <p>
      {{
        t(
          "HTTP 202 means the batch was received. Use batchId to query GET /v1/batches/{batchId} with the same project key. completed means processing finished; check rejected and errors for individual failures.",
          "HTTP 202 表示已接收。使用返回的 batchId 和同一项目密钥查询 GET /v1/batches/{batchId}。completed 表示处理结束，再检查 rejected 和 errors 是否存在失败记录。",
        )
      }}
    </p>
    <div class="code-box">
      <div>HTTP 202 · JSON</div>
      <pre>
{"batchId":"550e8400-e29b-41d4-a716-446655440099","received":1,"status":"pending"}</pre>
    </div>
    <p>
      <RouterLink :to="{ path: '/docs', hash: '#fields' }">{{
        t(
          "View Header, Body and Resp field descriptions →",
          "查看 Header、Body、Resp 字段描述 →",
        )
      }}</RouterLink>
    </p>
    <details class="protocol-advanced">
      <summary>
        {{
          t("More examples: model usage and scores", "更多示例：模型用量与评分")
        }}
      </summary>
      <h3>{{ t("Complete request example", "完整请求示例") }}</h3>
      <div class="code-box">
        <div>Header</div>
        <pre>
POST /v1/events/batch HTTP/1.1
Host: metertrail.com
Authorization: Bearer lm_live_&lt;YOUR_API_KEY&gt;
Content-Type: application/json</pre>
      </div>
      <p>
        {{
          t(
            "The example body is uncompressed, so Content-Encoding is omitted. Include Content-Encoding: gzip only when sending an actual gzip body. Replace the API key placeholder locally; never share your key.",
            "以下 Body 未压缩，故省略 Content-Encoding；只有实际发送 gzip 压缩内容时才设置 Content-Encoding: gzip。请在本地替换密钥占位符，不要公开密钥。",
          )
        }}
      </p>
      <div class="code-box">
        <div>Body · JSON</div>
        <pre>{{ bodyExample }}</pre>
      </div>
      <div class="code-box">
        <div>HTTP 202 · JSON</div>
        <pre>{{ receiptExample }}</pre>
      </div>
      <div class="code-box">
        <div>GET /v1/batches/{batchId} · JSON</div>
        <pre>{{ resultExample }}</pre>
      </div>
    </details>
  </div>
</template>
