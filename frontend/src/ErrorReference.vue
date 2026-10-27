<script setup lang="ts">
import { t } from "./lib";
const requestErrors = [
  [
    "400",
    "INVALID_JSON / INVALID_REQUEST",
    "JSON 或请求参数格式错误",
    "Malformed JSON or request parameters",
    "修正请求后再提交，不原样重试",
    "Correct the request before resending",
  ],
  [
    "400",
    "INVALID_SCHEMA",
    "显式传入的 schemaVersion 不是字符串 1.0；省略时默认 1.0",
    "Explicit schemaVersion must be the string 1.0; omission defaults to 1.0",
    "使用协议版本 1.0 和对象形式的请求体",
    "Use schema 1.0 and an object envelope",
  ],
  [
    "400",
    "INVALID_BATCH_SIZE",
    "事件数量不在 1–500 范围",
    "Event count is outside 1–500",
    "拆分或补全批次",
    "Send 1–500 events per batch",
  ],
  ["400", "INVALID_AGENT", "agent 传入时必须是对象，不能是 null、字符串或数组", "Explicit agent must be an object, not null, a string or array", "可省略整个 agent，三个子字段会采用默认值", "Omit agent to use the three default values"],
  [
    "400",
    "INVALID_SERVICENAME / INVALID_ENVIRONMENT / INVALID_INSTANCEID / INVALID_SDKNAME / INVALID_SDKVERSION",
    "agent 字段为空、类型错误或超过 128 字节",
    "Invalid agent fields or values over 128 UTF-8 bytes",
    "修正 agent；SDK 字段可省略，存在时必须有效",
    "Fix agent fields; optional SDK fields must be valid when supplied",
  ],
  [
    "401",
    "INVALID_API_KEY",
    "密钥缺失、无效、已撤销或项目已删除",
    "Missing, invalid or revoked key, or deleted project",
    "暂停上报，检查 Bearer 密钥及项目",
    "Pause reporting; check the Bearer key and project",
  ],
  [
    "404",
    "BATCH_NOT_FOUND",
    "仅查询状态接口：该项目下找不到批次",
    "Status query only: batch not found in this project",
    "检查 batchId 和密钥所属项目",
    "Check batchId and the key’s project",
  ],
  [
    "413",
    "PAYLOAD_TOO_LARGE",
    "压缩前或解压后请求体超过 1 MiB",
    "Compressed or decompressed body exceeds 1 MiB",
    "拆批；单条超限则缩减事件内容",
    "Split the batch; reduce oversized individual events",
  ],
  [
    "415",
    "INVALID_CONTENT_TYPE / UNSUPPORTED_ENCODING",
    "内容类型或压缩编码不支持",
    "Unsupported content type or encoding",
    "使用 application/json；编码可为 gzip、identity 或不设置",
    "Use application/json; use gzip, identity or no encoding header",
  ],
  [
    "400",
    "INVALID_GZIP",
    "gzip 内容无效",
    "Invalid gzip data",
    "修复压缩内容后重发",
    "Correct the gzip body before resending",
  ],
  [
    "429",
    "RATE_LIMITED",
    "请求过于频繁",
    "Too many requests",
    "遵循 Retry-After（当前 60 秒），加抖动并限制重试次数",
    "Honor Retry-After (currently 60 seconds), with jitter and bounded retries",
  ],
  [
    "429",
    "QUOTA_EXCEEDED",
    "额度不足、超额上限不足或付费订阅账期无效",
    "Insufficient quota/cap or inactive paid billing period",
    "暂停新增对象，检查用量、订阅和 /v1/quota；不要立即循环重试",
    "Pause new objects; inspect usage, subscription and /v1/quota; do not retry in a tight loop",
  ],
  [
    "503",
    "INGEST_BACKLOG_FULL",
    "待处理与隔离批次积压超过阈值",
    "Pending/quarantined ingestion backlog exceeds the limit",
    "保留事件身份，有限次数指数退避；持续失败联系管理员",
    "Preserve event identity; bounded exponential backoff; contact operator if persistent",
  ],
  [
    "503",
    "RATE_LIMIT_UNAVAILABLE",
    "请求限流服务暂时不可用",
    "Request rate limiting is temporarily unavailable",
    "保留事件身份，有限次数退避重试",
    "Preserve event identity and retry with bounded backoff",
  ],
  [
    "409",
    "CONFLICT",
    "通用异常处理：请求与现有记录冲突",
    "Shared error handler: request conflicts with an existing record",
    "检查重复或冲突数据，不盲目重试",
    "Inspect conflicting data rather than retrying blindly",
  ],

];
const eventErrors = [
  [
    "EVENT_TOO_LARGE",
    "单个事件超过 8 KiB",
    "Event exceeds 8 KiB",
    "缩减事件内容",
    "Reduce event size",
  ],
  [
    "UNKNOWN_FIELD / UNKNOWN_DATA_FIELD",
    "事件或 data 含未知字段",
    "Unknown event or data field",
    "删除协议不支持字段，不上传完整提示词/模型回答",
    "Remove unsupported fields; do not send complete prompts or responses",
  ],
  [
    "EVENT_ID_REQUIRED",
    "eventId 缺失、为 null、空字符串或全空白",
    "eventId is missing, null, empty or blank",
    "填写非空的 36 字符字符串，不限制 UUID 格式",
    "Supply a nonblank 36-character string; UUID formatting is not required",
  ],
  [
    "INVALID_EVENT_ID_TYPE",
    "eventId 类型错误，必须是字符串",
    "eventId must be a string",
    '使用 JSON 字符串，例如 "550e8400-e29b-41d4-a716-446655440000"',
    'Use a JSON string such as "550e8400-e29b-41d4-a716-446655440000"',
  ],
  [
    "EVENT_ID_LENGTH_MUST_BE_36",
    "eventId 长度错误：必须恰好 36 个字符，不限制排列格式",
    "eventId must be exactly 36 characters, with no pattern restriction",
    "保持项目内唯一，原样重试保留相同 ID 与内容",
    "Keep IDs unique within the project; preserve ID and content on identical retries",
  ],
  [
    "INVALID_EVENT_ID_FORMAT",
    "旧版本 UUID 格式错误；当前接口不再限制 UUID 格式",
    "Legacy UUID format error; current uploads no longer require a UUID pattern",
    "当前只要求非空字符串，长度恰好 36 字符",
    "Current IDs must be nonblank strings of exactly 36 characters",
  ],
  [
    "INVALID_EVENTID / INVALID_EVENT_ID",
    "旧版本的 eventId 综合错误码；新版本已拆分为上述具体原因",
    "Legacy combined eventId errors; new responses use the specific codes above",
    "检查字符串类型与 36 字符长度；仅原样重试保留原 ID，修改已接收事件应使用新 ID",
    "Check string type and 36-character length; preserve IDs only for unchanged retries",
  ],
  [
    "INVALID_TYPE / INVALID_OBJECTID / INVALID_REVISION",
    "对象类型、对象 ID 或版本无效",
    "Invalid object type, ID or revision",
    "type 为 trace/observation/score；objectId 最多 128 字节；revision 可省略默认 1，传入时须为正整数",
    "Use an allowed type, objectId up to 128 bytes and optional revision (default 1; positive integer when provided)",
  ],
  [
    "INVALID_TIMESTAMP / OUTSIDE_TIME_WINDOW",
    "时间无效或新对象不在允许时间窗",
    "Invalid timestamp or new object outside time window",
    "使用 UTC；新对象最早 7 天前，未来最多 5 分钟",
    "Use UTC; new objects may be up to 7 days old or 5 minutes in the future",
  ],
  [
    "OUTSIDE_RETENTION",
    "对象更新超出套餐保留期",
    "Object update is outside plan retention",
    "检查原对象与事件时间，不反复重试过期更新",
    "Check original object and event timestamps; do not repeatedly retry expired updates",
  ],
  [
    "INVALID_TRACE_ID / INVALID_SPAN_ID / INVALID_PARENT_SPAN",
    "链路或跨度 ID 格式不正确",
    "Invalid trace/span/parent span ID",
    "traceId 为 32 位十六进制；spanId/parentSpanId 为 16 位",
    "Use 32 hex characters for traceId and 16 for span IDs",
  ],
  [
    "INVALID_DATA / INVALID_NAME / INVALID_TARGETID",
    "data 或名称/目标 ID 不符合要求",
    "Invalid data, name or target ID",
    "按对象类型提供必填字段、正确类型及长度",
    "Supply required fields with valid types and lengths",
  ],
  [
    "INVALID_STATUS / INVALID_DURATION",
    "状态或耗时无效",
    "Invalid reported status or duration",
    "status 为 success/error/cancelled；耗时为非负整数",
    "Use success/error/cancelled and a nonnegative integer duration",
  ],
  [
    "INVALID_OBSERVATION_TYPE / INVALID_PROVIDER / INVALID_MODEL",
    "Observation 类型或模型信息无效",
    "Invalid observation type or model information",
    "使用允许的 observationType；generation 必填 provider、model、usage",
    "Use an allowed observationType; generation requires provider, model and usage",
  ],
  [
    "INVALID_USAGE",
    "Token 用量结构或数值无效",
    "Invalid token usage",
    "三个 Token 字段均需显式提供，可为 null；缓存输入不得大于已知总输入",
    "Provide all three token fields explicitly (nullable); cached input cannot exceed known total input",
  ],
  [
    "INVALID_SCORE_TARGET / INVALID_SCORE",
    "评分目标、valueType 或 value 无效",
    "Invalid score target, valueType or value",
    "目标类型为 trace/observation；值匹配 numeric/boolean/categorical",
    "Use trace/observation targets and a value matching numeric/boolean/categorical",
  ],
  [
    "INVALID_USERID / INVALID_CUSTOMERID / INVALID_FEATURE / INVALID_ERROR",
    "业务标识或错误描述字段无效",
    "Invalid business identifiers or error text",
    "业务标识最多 128 字节，error 最多 1 KiB，非空字符串",
    "Use nonempty strings; identifiers up to 128 bytes and error up to 1 KiB",
  ],
  [
    "EVENT_ID_CONFLICT",
    "相同 eventId 对应不同内容",
    "Same eventId has different content",
    "重试原事件时不改内容；新事件使用新 eventId",
    "Retry the original unchanged event; use a new eventId for a new event",
  ],
  [
    "REVISION_CONFLICT",
    "相同对象版本对应不同内容",
    "Same object revision has different content",
    "更正对象时使用更高 revision 和新 eventId",
    "Use a higher revision and new eventId for a correction",
  ],
  [
    "STALE_REVISION",
    "低于当前版本，计入 duplicates 而非 rejected",
    "Older revision; counted as duplicate, not rejected",
    "停止重试旧版本；需要更新时提交更高 revision",
    "Stop retrying old revisions; use a higher revision for updates",
  ],
  [
    "QUOTA_EXCEEDED",
    "混合批次中部分对象因额度不足被拒绝",
    "Some objects in a mixed batch rejected for quota",
    "处理额度后只补发被拒绝事件，保留身份并检查结果",
    "Resolve quota, resend rejected events with their identity preserved and inspect results",
  ],
];
</script>
<template>
  <div class="error-reference">
    <h3>{{ t("Request-level errors", "请求级错误编码") }}</h3>
    <p>
      {{
        t(
          "A non-2xx response rejects the request. Read the JSON code field; a network failure or generic 5xx may have no code.",
          "非 2xx 响应表示请求未成功接收。读取响应 JSON 的 code；网络失败或通用 5xx 不保证返回 code。",
        )
      }}
    </p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>HTTP</th>
            <th>{{ t("Code", "错误码") }}</th>
            <th>{{ t("Meaning", "含义") }}</th>
            <th>{{ t("Handling / retry", "处理与重试") }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in requestErrors" :key="row[1]">
            <td>{{ row[0] }}</td>
            <td>
              <code>{{ row[1] }}</code>
            </td>
            <td>{{ t(row[3], row[2]) }}</td>
            <td>{{ t(row[5], row[4]) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <h3>{{ t("Immediate parameter validation", "参数错误直接返回") }}</h3>
    <p>{{ t('Before enqueueing, every event is checked for required fields, types, lengths, UUIDs, timestamps and allowed values. Any invalid event returns HTTP 400; the entire batch is rejected, including otherwise valid events. No batchId is issued and no units are charged. errors[].index is zero-based; each invalid event reports its first error. Fix all reported errors and resubmit the entire batch. Do not repeatedly retry an unchanged 400 response.', '入队前逐条校验必填字段、类型、长度、UUID、时间格式与取值范围。任何一条不合法，直接返回 HTTP 400，整批均不接收（包括格式正确的其他事件），不生成 batchId、不扣 units。errors[].index 从 0 开始，每条不合法事件返回首个错误。修正后重新提交整批，不要原样循环重试 400。') }}</p>
    <div class="code-box"><div>HTTP 400 · JSON</div><pre>{"code":"EVENT_ID_LENGTH_MUST_BE_36","errors":[{"index":0,"field":"events[0].eventId","code":"EVENT_ID_LENGTH_MUST_BE_36"}]}</pre></div>
    <p>{{ t('This preflight does not query object history. Duplicate events, revision conflicts, current quota and retained-data rules are checked later; query batch status after a successful HTTP 200 receipt.', '预校验不查询对象历史。事件去重、对象版本冲突、当前额度与数据保留规则仍在后续检查；HTTP 200 接收后可查询批次处理结果。') }}</p>
    <h3>{{ t("Event error codes", "逐条事件错误编码") }}</h3>
    <p>
      {{
        t(
          "The following codes include immediate HTTP 400 validation errors and later processing results. HTTP 200 means received, not every event succeeded. Inspect accepted, duplicates, rejected and errors using the batch query API or Recent batches.",
          "下表同时包含直接返回 HTTP 400 的格式错误码和后续处理结果码。200 只表示接收，不表示每条事件成功。通过批次查询接口或“最近上报批次”查看 accepted、duplicates、rejected 和 errors。",
        )
      }}
    </p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{{ t("Code", "错误码") }}</th>
            <th>{{ t("Meaning", "含义") }}</th>
            <th>{{ t("Handling / retry", "处理与重试") }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in eventErrors" :key="row[0]">
            <td>
              <code>{{ row[0] }}</code>
            </td>
            <td>{{ t(row[2], row[1]) }}</td>
            <td>{{ t(row[4], row[3]) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p>
      {{
        t(
          "Identical events are duplicates without an error code. quarantined, failed and deleted are batch states, not successful completion; do not keep resending a quarantined batch. Ask the operator to repair/replay it.",
          "相同事件重发会计入 duplicates，不一定产生错误码。quarantined、failed、deleted 是批次状态，不能当作成功完成；隔离批次应由管理员修复重放，不要持续重复上报。",
        )
      }}
    </p>
  </div>
</template>
