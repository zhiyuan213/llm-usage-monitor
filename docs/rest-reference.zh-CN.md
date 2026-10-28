# REST 接入字段参考

POST https://metertrail.com/v1/events/batch；请求体 JSON。长度默认 UTF-8 字节，1 KiB=1024 字节。新事件使用当前 UTC 时间和新 ID，重试原事件保持身份与内容不变。data 路径属于 events[] 单项；data 是事件内容的 JSON 对象，请按事件类型选择需要的字段。

## 从最小请求开始

官网 Rest接入 展示 URL → Method → Header → Body → Resp，上报 Header / Body 参数表集中在左侧“接入字段”（/docs#fields），位于“SDK接入”下一项。上报 Method 为 POST。首次接入只需上报一条 Trace，记录名称、状态和耗时。模型 Token 用量与评分按需添加，无需一次填写全部参数。官网默认展示最小示例，完整字段参考放在独立“接入字段”栏目。

## 请求头 Header

| 字段名称 | 接入字段 | 字段类型 | 必填条件 | 长度 / 取值范围 | 示例 |
| --- | --- | --- | --- | --- | --- |
| Host | URL 中的服务器主机，由 HTTP 客户端自动生成 | string | 自动生成（HTTP/1.1） | 与 URL 主机一致，无需手动填写 | metertrail.com |
| Authorization | 项目 API 密钥认证 | string | 必填 | Bearer 后填写项目 API 密钥 | Bearer lm_live_<YOUR_API_KEY> |
| Content-Type | JSON 内容类型 | string | 必填 | 以 application/json 开头 | application/json |
| Content-Encoding | 可选请求体压缩 | string | 可选 | gzip、identity 或省略；压缩与解压后均 ≤1 MiB | gzip |

## Body：批次根对象

| 字段名称 | 接入字段 | 字段类型 | 必填条件 | 长度 / 取值范围 | 示例 |
| --- | --- | --- | --- | --- | --- |
| schemaVersion | 协议版本 | string | 必填 | 固定为 1.0 | "1.0" |
| agent | 应用及 SDK 上下文 | object | 必填 | 见 agent 字段表 | {"serviceName":"demo-api","environment":"test","instanceId":"demo-1"} |
| events | 需要上报的事件数组 | array<object> | 必填 | 1–500 条；每条事件 JSON ≤8 KiB UTF-8；请求体 ≤1 MiB | [{"eventId":"550e8400-e29b-41d4-a716-446655440000", "...":"..."}] |

## Body：agent 应用信息

| 字段名称 | 接入字段 | 字段类型 | 必填条件 | 长度 / 取值范围 | 示例 |
| --- | --- | --- | --- | --- | --- |
| agent.serviceName | 应用或服务名称 | string | 必填 | 非空；≤128 UTF-8 字节 | "demo-api" |
| agent.environment | 部署环境 | string | 必填 | 非空；≤128 UTF-8 字节 | "test" |
| agent.instanceId | 应用实例标识 | string | 必填 | 非空；≤128 UTF-8 字节 | "demo-process-1" |
| agent.sdkName | SDK 名称 | string | 可选 | 出现时非空；≤128 UTF-8 字节 | "metertrail-node" |
| agent.sdkVersion | SDK 版本 | string | 可选 | 出现时非空；≤128 UTF-8 字节 | "0.1.0" |

## Body：events[] 通用字段

| 字段名称 | 接入字段 | 字段类型 | 必填条件 | 长度 / 取值范围 | 示例 |
| --- | --- | --- | --- | --- | --- |
| eventId | 事件去重标识 | string (UUID) | 必填 | 36 字符 UUID；重试保持不变 | "550e8400-e29b-41d4-a716-446655440000" |
| type | 对象类型 | string | 必填 | trace / observation / score；≤16 字节 | "observation" |
| objectId | 项目及对象类型内唯一的对象标识 | string | 必填 | 非空；≤128 UTF-8 字节 | "obs-demo-1" |
| revision | 对象递增版本 | integer (int64) | 必填 | 1–9,223,372,036,854,775,807；客户端须避免整数精度丢失 | 1 |
| traceId | 链路标识 | string | Trace / Observation 必填 | 32 位十六进制 | "0123456789abcdef0123456789abcdef" |
| spanId | Observation 跨度标识 | string | Observation 必填 | 16 位十六进制 | "0123456789abcdef" |
| parentSpanId | 可选父跨度标识 | string \| null | 可选 | Observation 非 null 时为 16 位十六进制 | null |
| startedAt | 事件开始时间 | string (ISO-8601) | 必填 | ≤40 字节；UTC；新对象近 7 天，允许未来 5 分钟；更新受保留期限制 | "2026-10-23T00:00:00Z" |
| data | 按 type 区分的事件内容 | object | 必填 | 见以下 data 表；未知字段会被拒绝 | {"status":"success","durationMs":100} |

## Body：Trace / Observation 的 data

| 字段名称 | 接入字段 | 字段类型 | 必填条件 | 长度 / 取值范围 | 示例 |
| --- | --- | --- | --- | --- | --- |
| data.name | Trace 名称或可选 Observation 名称 | string | Trace 必填；Observation 可选 | Trace 非空且≤256字节；Observation 受8KiB事件限制 | "Document analysis" |
| data.status | 上报的业务执行状态 | string | 必填 | success / error / cancelled，原样展示 | "success" |
| data.durationMs | 耗时，单位毫秒 | integer | 必填 | 0–9,000,000,000,000，不可为 null | 1850 |
| data.error | 业务错误描述 | string \| null | 可选 | 非 null 时为非空字符串；≤1024 UTF-8字节 | "Upstream timeout" |
| data.userId | 用户标识 | string \| null | 可选 | 非 null 时非空；≤128 UTF-8 字节 | "demo-userId" |
| data.customerId | 客户标识 | string \| null | 可选 | 非 null 时非空；≤128 UTF-8 字节 | "demo-customerId" |
| data.feature | 功能标识 | string \| null | 可选 | 非 null 时非空；≤128 UTF-8 字节 | "demo-feature" |
| data.observationType | Observation 子类型 | string | Observation 必填 | generation / span / tool / retrieval / embedding / other | "generation" |
| data.provider | 模型供应商 | string | generation 必填 | 非空；≤64 UTF-8 字节 | "example" |
| data.model | 模型名称 | string | generation 必填 | 非空；≤256 UTF-8 字节 | "example-model" |
| data.usage | Token 用量 | object | generation 必填；其他 Observation 可选 | 出现时须包含以下三个用量字段 | {"inputTokens":1500,"outputTokens":400,"cachedInputTokens":500} |

## Body：data.usage 用量字段

| 字段名称 | 接入字段 | 字段类型 | 必填条件 | 长度 / 取值范围 | 示例 |
| --- | --- | --- | --- | --- | --- |
| data.usage.inputTokens | 总输入 Token，包含缓存输入 | integer \| null | usage 出现时必填 | 0–9,000,000,000,000 或 null；未知不能填 0 | 1500 |
| data.usage.outputTokens | 输出 Token | integer \| null | usage 出现时必填 | 0–9,000,000,000,000 或 null | 400 |
| data.usage.cachedInputTokens | 输入 Token 中命中缓存的部分 | integer \| null | usage 出现时必填 | 0–9,000,000,000,000 或 null；非 null 时 inputTokens 必须已知且不小于此值 | 500 |

## Body：Score 的 data（仅 type=score）

| 字段名称 | 接入字段 | 字段类型 | 必填条件 | 长度 / 取值范围 | 示例 |
| --- | --- | --- | --- | --- | --- |
| data.name | 评分名称 | string | 必填 | 非空；≤128 UTF-8 字节 | "quality" |
| data.targetType | 被评分对象类型 | string | 必填 | trace / observation | "observation" |
| data.targetId | 同项目内被评分对象的 objectId | string | 必填 | 非空；≤128 UTF-8 字节 | "obs-demo-1" |
| data.valueType | 评分值类型 | string | 必填 | numeric / boolean / categorical | "numeric" |
| data.value | 评分值 | number \| boolean \| string | 必填 | numeric：有限数字；boolean：布尔值；categorical：非空字符串，≤128 UTF-16 单元；整体仍受8KiB限制 | 0.95 / true / "good" |

## 响应：POST 202 Accepted

| 字段名称 | 接入字段 | 字段类型 | 必填条件 | 长度 / 取值范围 | 示例 |
| --- | --- | --- | --- | --- | --- |
| batchId | 批次接收标识 | string (UUID) | 必填 | 36 字符 UUID；标识本次批次 | "550e8400-e29b-41d4-a716-446655440099" |
| received | 提交事件数 | integer | 必填 | 1–500 | 3 |
| status | 初始平台批次状态 | string | 必填 | 上报时固定 pending，非实时进度；需要实时状态时调用 GET https://metertrail.com/v1/batches/{batchId} | "pending" |

## 完整请求示例

```http
POST /v1/events/batch HTTP/1.1
Host: metertrail.com
Authorization: Bearer lm_live_<YOUR_API_KEY>
Content-Type: application/json
```

此示例未压缩，不设置 Content-Encoding。实际压缩为 gzip 后才能设置 Content-Encoding: gzip。

```json
{
  "schemaVersion": "1.0",
  "agent": {
    "serviceName": "demo-api",
    "environment": "test",
    "instanceId": "demo-1"
  },
  "events": [
    {
      "eventId": "550e8400-e29b-41d4-a716-446655440000",
      "type": "trace",
      "objectId": "trace-demo-1",
      "revision": 1,
      "traceId": "0123456789abcdef0123456789abcdef",
      "startedAt": "2026-10-23T00:00:00Z",
      "data": {
        "name": "Document analysis",
        "status": "success",
        "durationMs": 1850,
        "userId": "demo-user",
        "customerId": "demo-customer",
        "feature": "document-analysis"
      }
    },
    {
      "eventId": "550e8400-e29b-41d4-a716-446655440001",
      "type": "observation",
      "objectId": "obs-demo-1",
      "revision": 1,
      "traceId": "0123456789abcdef0123456789abcdef",
      "spanId": "0123456789abcdef",
      "parentSpanId": null,
      "startedAt": "2026-10-23T00:00:00Z",
      "data": {
        "observationType": "generation",
        "status": "success",
        "durationMs": 1850,
        "provider": "example",
        "model": "example-model",
        "usage": {
          "inputTokens": 1500,
          "outputTokens": 400,
          "cachedInputTokens": 500
        }
      }
    },
    {
      "eventId": "550e8400-e29b-41d4-a716-446655440002",
      "type": "score",
      "objectId": "score-demo-1",
      "revision": 1,
      "startedAt": "2026-10-23T00:00:00Z",
      "data": {
        "name": "quality",
        "targetType": "observation",
        "targetId": "obs-demo-1",
        "valueType": "numeric",
        "value": 0.95
      }
    }
  ]
}
```

## 响应示例

```json
{"batchId":"550e8400-e29b-41d4-a716-446655440099","received":3,"status":"pending"}
```

HTTP 202 表示批次已接收；上报响应 status 固定 pending，非实时处理状态。需要实时状态时，使用同一项目密钥请求 GET https://metertrail.com/v1/batches/{batchId}。

错误与逐条拒绝码参见 [错误编码表](error-codes.zh-CN.md)。
