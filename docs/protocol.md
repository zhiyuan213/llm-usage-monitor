# Event protocol 1.0

## Authentication and envelope

`POST /v1/events/batch`, `Content-Type: application/json`, `Authorization: Bearer lm_live_…`. HTTPS is required outside local development. Optional `Content-Encoding: gzip` is accepted. Both compressed and decompressed bodies are capped at 1 MiB. A batch holds 1–500 events; individual events are limited to 8 KiB UTF-8.

```json
{
  "schemaVersion": "1.0",
  "agent": {"serviceName":"resume-api","environment":"production","instanceId":"process-unique-id"},
  "events": [{
    "eventId":"550e8400-e29b-41d4-a716-446655440000",
    "type":"observation", "objectId":"obs-1", "revision":1,
    "traceId":"0123456789abcdef0123456789abcdef", "spanId":"0123456789abcdef",
    "startedAt":"2026-10-21T06:00:00Z",
    "data": {
      "observationType":"generation", "provider":"example", "model":"example-model",
      "durationMs":1850, "status":"success",
      "usage":{"inputTokens":1500,"outputTokens":400,"cachedInputTokens":500},
      "userId":"user-1", "customerId":"customer-1", "feature":"resume-analysis"
    }
  }]
}
```

Use a current UTC timestamp when sending. The documentation's timestamp is illustrative. `scripts/generate-example.mjs` generates current, valid test data.

## Objects

| Field | Rule |
| --- | --- |
| `eventId` | UUID; preserved verbatim during retries |
| `type` | `trace`, `observation`, `score` |
| `objectId` | Nonempty, ≤128 UTF-8 bytes, unique per project and type |
| `revision` | Positive integral 64-bit revision |
| `startedAt` | ISO-8601 UTC; new objects within 7 days, future tolerance 5 minutes |
| `traceId` | 32 hex characters, required for Trace and Observation |
| `spanId` | 16 hex characters, required for Observation |
| `parentSpanId` | Optional 16 hex characters or null |
| `agent` | `serviceName`, `environment`, `instanceId` required, each ≤128 bytes; optional `sdkName`, `sdkVersion` |

Trace data: `name`, `status`, `durationMs`; optional `userId`, `customerId`, `feature`, `error`.

Observation data: `observationType` (`generation`, `span`, `tool`, `retrieval`, `embedding`, `other`), `status`, `durationMs`. Generation requires `provider` (≤64 bytes), `model` (≤256 bytes), and `usage`. Optional name and business identifiers.

Usage requires explicit `inputTokens`, `outputTokens`, and `cachedInputTokens`: nonnegative integers or null. Cached input cannot exceed total input; non-null cached input requires known total input. Unknown fields are rejected to avoid accidental prompt/response collection. Numeric token/duration limits are 9,000,000,000,000.

Score data: `name`, `targetType` (`trace` or `observation`), `targetId`, `valueType` (`numeric`, `boolean`, `categorical`), `value`. Numeric must be finite; categorical strings are nonempty and at most 128 characters. Targets resolve only inside the project. Out-of-order parents are allowed and displayed as unmatched; no implicit billable parent is created.

Status: `success`, `error`, `cancelled`. Error is a string ≤1 KiB. Business identifiers each ≤128 bytes. Complete prompts, responses, arbitrary metadata, and unsupported pricing dimensions are intentionally not accepted by this schema.

## Durable receipt and results

```json
{"batchId":"uuid","received":3,"status":"pending"}
```

HTTP 202 is returned only after quota reservation, identity writes, and batch/event persistence commit. The upload receipt always has `status: "pending"`; this fixed value is not live progress. Query only when needed, using the same project key with `GET https://metertrail.com/v1/batches/{batchId}`:

```json
{"status":"completed","received":3,"accepted":2,"duplicates":0,"rejected":1,"errors":[{"index":2,"code":"INVALID_USAGE"}]}
```

Indices are zero-based. Individual field errors allow the valid siblings through. Broken envelope/JSON fails the whole request. All-new objects refused solely for quota return 429 `QUOTA_EXCEEDED`; mixed batches expose rejected indices in the final result. `STALE_REVISION` is counted under duplicates and listed in errors for visibility.

| HTTP | Behavior |
| --- | --- |
| 400 | Invalid envelope; correct fields before resending |
| 401/403 | Invalid/revoked key; pause, notify owner |
| 413 | Split batch; reject a single oversize event |
| 429 `RATE_LIMITED` | Honor `Retry-After`, jitter retries |
| 429 `QUOTA_EXCEEDED` | Pause new events; review billing or call `/v1/quota` |
| 503 | Temporary infrastructure problem; bounded exponential retry |

Network retries must preserve original IDs, revision, timestamp, and content. JSON object key order does not affect the canonical hash. Numeric lexical differences are not normalized beyond JSON parsing; clients should replay the original event representation.

## Idempotency and units

Transport identity is `(project,eventId)` and content hash. Reusing an ID for different content yields `EVENT_ID_CONFLICT`. Object identity is `(project,type,objectId)`: first acceptance is 1 unit, subsequent higher revisions are 0 units. A new event ID with the same revision/content is still a duplicate. Equal revision with different content is `REVISION_CONFLICT`.

The workspace row lock serializes reservations across all its projects, so concurrent requests cannot oversell the last unit or overage cap. Units are attributed to the current acceptance period, never the event's historical business day. Paid workspaces with expired/unconfirmed billing periods refuse new objects until a valid provider update; updates remain allowed within retention.

Worker results can also be `quarantined` (durable, awaiting repair/replay), `failed` (explicit permanent failure), or `deleted` (project removed). Email-verified cancellation of a quarantined batch releases each eligible reservation once. A corrected higher revision with a new event ID re-reserves a previously released object once. Settled objects and objects with other pending updates are not refunded. Closed-period adjustments require operator review. SDKs surface non-completed terminal states instead of claiming successful settlement.

Tier rates are exact decimal values: 100k–1m $8/100k; 1m–10m $7/100k; 10m–50m $6.5/100k; above 50m $6/100k. Free 50k, Core/Pro 100k included; Core $29, Pro $199. Add-on packs are unavailable pending product decisions.

## Cost evidence

Rates are USD per million tokens, versioned by project/provider/model/effective time. Cost = `(input-cached)*inputRate + cached*cachedRate + output*outputRate`, divided by 1,000,000. Any missing price or required usage component yields null/unknown, not zero. A corrected object revision replaces its current analytical value atomically. Unsupported provider pricing dimensions require a future schema version.

## Error-code reference

See the [Chinese request-level and per-event error tables](error-codes.zh-CN.md), or the bilingual tables at `/docs#retries`. Error codes are stable protocol values and are not translated.

Full Header / Body / response field definitions and examples: [REST integration reference (Chinese)](rest-reference.zh-CN.md), also available bilingually at `/docs#fields`; request and response examples remain at `/docs#protocol`.

### Purchased units and quota

`GET /v1/quota` additionally returns `addon_units` (current-period purchased units), `addon_consumed` (current-period historical pack consumption, including later refunded packs), `overage_units` (authorized usage billed separately) and `available_units` (remaining base plus valid purchased units). An inactive paid subscription has zero available units. A pack does not reprice previously accrued overage. Pack expiry and refunds preserve historical consumption and cannot create a new, unconsented charge.

## 接入场景
- 单次模型调用：请求完成后同一批次发送 Trace 和 generation Observation，共 2 个新对象、2 units。
- 多次模型调用：可逐步发送 Observation，最后发送最终 Trace；所有步骤共用 traceId。示例为三批（1、1、2 个事件），共 4 个新对象、4 units。也允许完成后同批发送。
- 可选评分：单独请求发送 Score，通过 targetType / targetId 指向同一项目中已处理成功的 Observation；不重发原对象，新评分为 1 unit。
- 网络分批边界不等于业务请求边界。SDK 的 record 为内存排队，flush 推送缓冲；HTTP 202 / pending 是接收回执，按需查询批次处理结果。新业务生成新 ID；重试保留原 ID 和内容。
- 官网 REST / SDK 场景代码由 `scripts/sync-scenario-examples.mjs` 同源生成。`scripts/check-scenario-examples.mjs` 执行三种语言的全部场景并校验实际批次、对象关联、字段和评分目标。
