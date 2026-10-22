<script setup lang="ts">
import { t } from "./lib";
const sections = [
  {
    en: "Request headers",
    zh: "请求头 Header",
    rows: [
      [
        "Authorization",
        "Project API key authentication",
        "项目 API 密钥认证",
        "string",
        "Required",
        "必填",
        "Bearer followed by your project API key",
        "Bearer 后填写项目 API 密钥",
        "Bearer lm_live_<YOUR_API_KEY>",
      ],
      [
        "Content-Type",
        "JSON media type",
        "JSON 内容类型",
        "string",
        "Required",
        "必填",
        "Must start with application/json",
        "以 application/json 开头",
        "application/json",
      ],
      [
        "Content-Encoding",
        "Optional body compression",
        "可选请求体压缩",
        "string",
        "Optional",
        "可选",
        "gzip, identity or omitted; compressed and decompressed body ≤1 MiB",
        "gzip、identity 或省略；压缩与解压后均 ≤1 MiB",
        "gzip",
      ],
    ],
  },
  {
    en: "Body: batch envelope",
    zh: "Body：批次根对象",
    rows: [
      [
        "schemaVersion",
        "Protocol version",
        "协议版本",
        "string",
        "Required",
        "必填",
        "Exactly 1.0",
        "固定为 1.0",
        '"1.0"',
      ],
      [
        "agent",
        "Application and SDK context",
        "应用及 SDK 上下文",
        "object",
        "Required",
        "必填",
        "See agent table",
        "见 agent 字段表",
        '{"serviceName":"demo-api","environment":"test","instanceId":"demo-1"}',
      ],
      [
        "events",
        "Events to ingest",
        "需要上报的事件数组",
        "array<object>",
        "Required",
        "必填",
        "1–500 events; each event JSON ≤8 KiB UTF-8; body ≤1 MiB",
        "1–500 条；每条事件 JSON ≤8 KiB UTF-8；请求体 ≤1 MiB",
        '[{"eventId":"550e8400-e29b-41d4-a716-446655440000", "...":"..."}]',
      ],
    ],
  },
  {
    en: "Body: agent",
    zh: "Body：agent 应用信息",
    rows: [
      [
        "agent.serviceName",
        "Application/service name",
        "应用或服务名称",
        "string",
        "Required",
        "必填",
        "Nonempty; ≤128 UTF-8 bytes",
        "非空；≤128 UTF-8 字节",
        '"demo-api"',
      ],
      [
        "agent.environment",
        "Deployment environment",
        "部署环境",
        "string",
        "Required",
        "必填",
        "Nonempty; ≤128 UTF-8 bytes",
        "非空；≤128 UTF-8 字节",
        '"test"',
      ],
      [
        "agent.instanceId",
        "Application instance identifier",
        "应用实例标识",
        "string",
        "Required",
        "必填",
        "Nonempty; ≤128 UTF-8 bytes",
        "非空；≤128 UTF-8 字节",
        '"demo-process-1"',
      ],
      [
        "agent.sdkName",
        "SDK name",
        "SDK 名称",
        "string",
        "Optional",
        "可选",
        "Nonempty when present; ≤128 UTF-8 bytes",
        "出现时非空；≤128 UTF-8 字节",
        '"metertrail-node"',
      ],
      [
        "agent.sdkVersion",
        "SDK version",
        "SDK 版本",
        "string",
        "Optional",
        "可选",
        "Nonempty when present; ≤128 UTF-8 bytes",
        "出现时非空；≤128 UTF-8 字节",
        '"0.1.0"',
      ],
    ],
  },
  {
    en: "Body: events[] common fields",
    zh: "Body：events[] 通用字段",
    rows: [
      [
        "eventId",
        "Event identity for deduplication",
        "事件去重标识",
        "string (UUID)",
        "Required",
        "必填",
        "36-character UUID; unchanged on retries",
        "36 字符 UUID；重试保持不变",
        '"550e8400-e29b-41d4-a716-446655440000"',
      ],
      [
        "type",
        "Object category",
        "对象类型",
        "string",
        "Required",
        "必填",
        "trace / observation / score; ≤16 bytes",
        "trace / observation / score；≤16 字节",
        '"observation"',
      ],
      [
        "objectId",
        "Object identity within project and type",
        "项目及对象类型内唯一的对象标识",
        "string",
        "Required",
        "必填",
        "Nonempty; ≤128 UTF-8 bytes",
        "非空；≤128 UTF-8 字节",
        '"obs-demo-1"',
      ],
      [
        "revision",
        "Monotonically increasing object revision",
        "对象递增版本",
        "integer (int64)",
        "Required",
        "必填",
        "1–9,223,372,036,854,775,807; avoid client-side integer precision loss",
        "1–9,223,372,036,854,775,807；客户端须避免整数精度丢失",
        "1",
      ],
      [
        "traceId",
        "Trace identifier",
        "链路标识",
        "string",
        "Required for Trace / Observation",
        "Trace / Observation 必填",
        "32 hexadecimal characters",
        "32 位十六进制",
        '"0123456789abcdef0123456789abcdef"',
      ],
      [
        "spanId",
        "Observation span identifier",
        "Observation 跨度标识",
        "string",
        "Required for Observation",
        "Observation 必填",
        "16 hexadecimal characters",
        "16 位十六进制",
        '"0123456789abcdef"',
      ],
      [
        "parentSpanId",
        "Optional parent span identifier",
        "可选父跨度标识",
        "string | null",
        "Optional",
        "可选",
        "For Observation, 16 hexadecimal characters when non-null",
        "Observation 非 null 时为 16 位十六进制",
        "null",
      ],
      [
        "startedAt",
        "Event start time",
        "事件开始时间",
        "string (ISO-8601)",
        "Required",
        "必填",
        "≤40 bytes; UTC; new objects within 7 days and at most 5 minutes ahead; updates subject to retention",
        "≤40 字节；UTC；新对象近 7 天，允许未来 5 分钟；更新受保留期限制",
        '"2026-10-23T00:00:00Z"',
      ],
      [
        "data",
        "Type-specific payload",
        "按 type 区分的事件内容",
        "object",
        "Required",
        "必填",
        "Use fields for the selected event type; unsupported fields are rejected",
        "按事件类型填写；不支持的字段会被拒绝",
        '{"status":"success","durationMs":100}',
      ],
    ],
  },
  {
    en: "Body: Trace / Observation data",
    zh: "Body：Trace / Observation 的 data",
    rows: [
      [
        "data.name",
        "Trace or optional observation name",
        "Trace 名称或可选 Observation 名称",
        "string",
        "Required for Trace; optional for Observation",
        "Trace 必填；Observation 可选",
        "Trace: nonempty, ≤256 bytes; Observation: subject to 8 KiB event cap",
        "Trace 非空且≤256字节；Observation 受8KiB事件限制",
        '"Document analysis"',
      ],
      [
        "data.status",
        "Reported execution result",
        "上报的业务执行状态",
        "string",
        "Required",
        "必填",
        "success / error / cancelled; displayed as reported",
        "success / error / cancelled，原样展示",
        '"success"',
      ],
      [
        "data.durationMs",
        "Duration in milliseconds",
        "耗时，单位毫秒",
        "integer",
        "Required",
        "必填",
        "0–9,000,000,000,000; not nullable",
        "0–9,000,000,000,000，不可为 null",
        "1850",
      ],
      [
        "data.error",
        "Business error description",
        "业务错误描述",
        "string | null",
        "Optional",
        "可选",
        "When non-null: nonempty string, ≤1024 UTF-8 bytes",
        "非 null 时为非空字符串；≤1024 UTF-8字节",
        '"Upstream timeout"',
      ],
      [
        "data.userId",
        "User identifier",
        "用户标识",
        "string | null",
        "Optional",
        "可选",
        "Nonempty when non-null; ≤128 UTF-8 bytes",
        "非 null 时非空；≤128 UTF-8 字节",
        '"demo-userId"',
      ],
      [
        "data.customerId",
        "Customer identifier",
        "客户标识",
        "string | null",
        "Optional",
        "可选",
        "Nonempty when non-null; ≤128 UTF-8 bytes",
        "非 null 时非空；≤128 UTF-8 字节",
        '"demo-customerId"',
      ],
      [
        "data.feature",
        "Feature identifier",
        "功能标识",
        "string | null",
        "Optional",
        "可选",
        "Nonempty when non-null; ≤128 UTF-8 bytes",
        "非 null 时非空；≤128 UTF-8 字节",
        '"demo-feature"',
      ],
      [
        "data.observationType",
        "Observation subtype",
        "Observation 子类型",
        "string",
        "Required for Observation",
        "Observation 必填",
        "generation / span / tool / retrieval / embedding / other",
        "generation / span / tool / retrieval / embedding / other",
        '"generation"',
      ],
      [
        "data.provider",
        "Model provider",
        "模型供应商",
        "string",
        "Required for generation",
        "generation 必填",
        "Nonempty; ≤64 UTF-8 bytes",
        "非空；≤64 UTF-8 字节",
        '"example"',
      ],
      [
        "data.model",
        "Model name",
        "模型名称",
        "string",
        "Required for generation",
        "generation 必填",
        "Nonempty; ≤256 UTF-8 bytes",
        "非空；≤256 UTF-8 字节",
        '"example-model"',
      ],
      [
        "data.usage",
        "Token usage",
        "Token 用量",
        "object",
        "Required for generation; optional for other observations",
        "generation 必填；其他 Observation 可选",
        "When present, all three token fields below are required",
        "出现时须包含以下三个用量字段",
        '{"inputTokens":1500,"outputTokens":400,"cachedInputTokens":500}',
      ],
    ],
  },
  {
    en: "Body: data.usage fields",
    zh: "Body：data.usage 用量字段",
    rows: [
      [
        "data.usage.inputTokens",
        "Total input tokens, including cached input",
        "总输入 Token，包含缓存输入",
        "integer | null",
        "Required when usage is present",
        "usage 出现时必填",
        "0–9,000,000,000,000 or null; do not use zero for unknown usage",
        "0–9,000,000,000,000 或 null；未知不能填 0",
        "1500",
      ],
      [
        "data.usage.outputTokens",
        "Output tokens",
        "输出 Token",
        "integer | null",
        "Required when usage is present",
        "usage 出现时必填",
        "0–9,000,000,000,000 or null",
        "0–9,000,000,000,000 或 null",
        "400",
      ],
      [
        "data.usage.cachedInputTokens",
        "Cached subset of input tokens",
        "输入 Token 中命中缓存的部分",
        "integer | null",
        "Required when usage is present",
        "usage 出现时必填",
        "0–9,000,000,000,000 or null; when known, inputTokens must also be known and at least this value",
        "0–9,000,000,000,000 或 null；非 null 时 inputTokens 必须已知且不小于此值",
        "500",
      ],
    ],
  },
  {
    en: "Body: Score data (type = score only)",
    zh: "Body：Score 的 data（仅 type=score）",
    rows: [
      [
        "data.name",
        "Score name",
        "评分名称",
        "string",
        "Required",
        "必填",
        "Nonempty; ≤128 UTF-8 bytes",
        "非空；≤128 UTF-8 字节",
        '"quality"',
      ],
      [
        "data.targetType",
        "Target object type",
        "被评分对象类型",
        "string",
        "Required",
        "必填",
        "trace / observation",
        "trace / observation",
        '"observation"',
      ],
      [
        "data.targetId",
        "Target objectId in the same project",
        "同项目内被评分对象的 objectId",
        "string",
        "Required",
        "必填",
        "Nonempty; ≤128 UTF-8 bytes",
        "非空；≤128 UTF-8 字节",
        '"obs-demo-1"',
      ],
      [
        "data.valueType",
        "Score value type",
        "评分值类型",
        "string",
        "Required",
        "必填",
        "numeric / boolean / categorical",
        "numeric / boolean / categorical",
        '"numeric"',
      ],
      [
        "data.value",
        "Score value",
        "评分值",
        "number | boolean | string",
        "Required",
        "必填",
        "numeric: finite number; boolean: boolean; categorical: nonempty string, ≤128 UTF-16 units; event cap still applies",
        "numeric：有限数字；boolean：布尔值；categorical：非空字符串，≤128 UTF-16 单元；整体仍受8KiB限制",
        '0.95 / true / "good"',
      ],
    ],
  },
  {
    en: "Response: POST 202 Accepted",
    zh: "响应：POST 202 Accepted",
    rows: [
      [
        "batchId",
        "Persisted batch identifier",
        "持久化批次标识",
        "string (UUID)",
        "Required",
        "必填",
        "36 characters; use to query results",
        "36 字符；用于查询处理结果",
        '"550e8400-e29b-41d4-a716-446655440099"',
      ],
      [
        "received",
        "Number of submitted events",
        "提交事件数",
        "integer",
        "Required",
        "必填",
        "1–500",
        "1–500",
        "3",
      ],
      [
        "status",
        "Initial platform batch state",
        "初始平台批次状态",
        "string",
        "Required",
        "必填",
        "pending; does not mean all events succeeded",
        "固定为 pending；不代表全部处理成功",
        '"pending"',
      ],
    ],
  },
  {
    en: "Result query: GET /v1/batches/{batchId}",
    zh: "结果查询：GET /v1/batches/{batchId}",
    rows: [
      [
        "batchId (path)",
        "Batch ID from POST receipt",
        "POST 接收响应中的批次 ID",
        "string (UUID)",
        "Required",
        "必填",
        "36 characters; requires Authorization for the same project",
        "36 字符；Header 仍需同项目 Authorization",
        "550e8400-e29b-41d4-a716-446655440099",
      ],
      [
        "status (response)",
        "Platform batch processing state",
        "平台批次处理状态",
        "string",
        "Required",
        "必填",
        "pending / completed / quarantined / failed / deleted",
        "pending / completed / quarantined / failed / deleted",
        '"completed"',
      ],
      [
        "received / accepted / duplicates / rejected (response)",
        "Submitted / accepted / duplicate / rejected event counts",
        "提交 / 接受 / 重复 / 拒绝的事件数量",
        "integer",
        "Required",
        "必填",
        "Each 0–500; inspect final results after processing",
        "各自为0–500；最终结果以批次处理完成后的响应为准",
        "3 / 2 / 0 / 1",
      ],
      [
        "errors (response)",
        "Per-event error/result list",
        "逐条事件错误或提示列表",
        "array<object>",
        "Required",
        "必填",
        "[] when empty; each item contains index and code",
        "无错误为 []；每项含 index、code",
        '[{"index":2,"code":"INVALID_USAGE"}]',
      ],
      [
        "errors[].index",
        "Zero-based index in submitted events",
        "提交 events 数组中的从0开始的下标",
        "integer",
        "Required",
        "必填",
        "0–499",
        "0–499",
        "2",
      ],
      [
        "errors[].code",
        "Stable protocol result/error code",
        "固定协议错误码或结果码",
        "string",
        "Required",
        "必填",
        "See Errors & retries",
        "值与处理方式见“错误与重试”表",
        '"INVALID_USAGE"',
      ],
    ],
  },
];
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
    <details class="protocol-advanced">
      <summary>
        {{
          t(
            "Field descriptions: Header / Body / Resp",
            "字段说明：Header / Body / Resp",
          )
        }}
      </summary>
      <p>
        {{
          t(
            "The tables below describe API parameters. data is a nested JSON object inside each event. Choose fields for the event type you send; you do not need every field. String lengths are UTF-8 bytes unless stated otherwise.",
            "以下是接口参数说明。data 是每条事件内的 JSON 对象，按事件类型选择需要的字段即可，不需要全部填写。字符串长度默认按 UTF-8 字节计算。",
          )
        }}
      </p>
      <section v-for="section in sections" :key="section.en">
        <h3>{{ t(section.en, section.zh) }}</h3>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{{ t("Field name", "字段名称") }}</th>
                <th>{{ t("Description", "字段描述") }}</th>
                <th>{{ t("Type", "字段类型") }}</th>
                <th>{{ t("Required", "必填条件") }}</th>
                <th>{{ t("Length / constraints", "长度 / 取值范围") }}</th>
                <th>{{ t("Example", "示例") }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in section.rows" :key="row[0]">
                <td>
                  <code>{{ row[0] }}</code>
                </td>
                <td>{{ t(row[1], row[2]) }}</td>
                <td>
                  <code>{{ row[3] }}</code>
                </td>
                <td>{{ t(row[4], row[5]) }}</td>
                <td>{{ t(row[6], row[7]) }}</td>
                <td>
                  <code>{{ row[8] }}</code>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
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
