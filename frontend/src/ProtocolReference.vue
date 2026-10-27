<script setup lang="ts">
import { t } from "./lib";
import {
  bodyExample,
  receiptExample,
  minimalBody,
  requestUrl,
  requestMethod,
  requestHeaders,
  minimalReceiptExample,
} from "./protocol-reference";
</script>
<template>
  <div class="protocol-reference">
    <div class="api-endpoint">
      <p>
        <strong>URL：</strong><code>{{ requestUrl }}</code>
      </p>
      <p>
        <strong>Method：</strong><code>{{ requestMethod }}</code>
      </p>
    </div>
    <h3>Header</h3>
    <div class="code-box">
      <div>{{ t("Request headers", "请求头示例") }}</div>
      <pre>{{ requestHeaders }}</pre>
    </div>
    <p>
      {{
        t(
          "Host is supplied automatically from the URL. Set Authorization and Content-Type as shown. Content-Encoding is optional and is omitted for this uncompressed example.",
          "Host 由客户端根据 URL 自动生成；按示例设置 Authorization 和 Content-Type。Content-Encoding 为可选项，此示例未压缩，所以不设置。",
        )
      }}
    </p>
    <h3>Body</h3>
    <p>{{ t('One request + one model call: 2 units. Model and Token columns come from the linked generation Observation, not the Trace itself. Replace the sample values and timestamp before sending.', '一条请求 + 一条模型调用，共 2 units。列表中的模型和 Token 来自同一 traceId 下的 generation Observation，不能只发送 Trace。上报前替换示例值与时间。') }}</p>
    <table class="example-field-map">
      <thead><tr><th>{{ t('Console field', '列表字段') }}</th><th>{{ t('Reported key', '上报字段') }}</th></tr></thead>
      <tbody>
        <tr><td>{{ t('User / customer', '用户 / 客户') }}</td><td><code>trace.data.userId / customerId</code></td></tr>
        <tr><td>{{ t('Feature', '功能') }}</td><td><code>trace.data.feature</code></td></tr>
        <tr><td>{{ t('Provider / model', '供应商 / 模型') }}</td><td><code>observation.data.provider / model</code></td></tr>
        <tr><td>Token</td><td><code>observation.data.usage.inputTokens / outputTokens / cachedInputTokens</code></td></tr>
      </tbody>
    </table>
    <p>{{ t('Configure matching provider/model prices in Project settings before ingestion to estimate cost. For example/example-model, demonstration USD rates per million Tokens of input 3, output 8 and cached input 0.5 yield US$0.00645 for this payload. Cached input is included in input, not added again. Missing pricing remains unknown; old records are not automatically repriced. These are demonstration prices only.', '估算费用需要先在项目设置配置匹配的供应商与模型价格。例如 example / example-model，每百万 Token 美元价格：输入 3、输出 8、缓存输入 0.5，本示例费用为 US$0.00645。缓存输入已包含在输入中，不重复累加。未配置价格时费用仍为未知；旧记录不会自动重算。以上仅为演示价格。') }}</p>
    <p>
      {{
        t(
          "Use actual startedAt and data. schemaVersion defaults to 1.0; agent defaults to serviceName-1 / environment-1 / instanceId-1; revision defaults to 1. Only omitted fields get defaults; null or invalid values are rejected. Set agent metadata when you need to identify your application.",
          "startedAt 和 data 填实际值。schemaVersion 可省略，默认 1.0；agent 可省略，三个子字段分别默认 serviceName-1、environment-1、instanceId-1；revision 可省略，默认 1。仅省略时使用默认值，null 或非法值不会自动修正。需要区分应用来源时，再填写 agent。",
        )
      }}
    </p>
    <table class="example-field-map"><thead><tr><th>{{ t('Scenario', '使用场景') }}</th><th>{{ t('Identity rule', 'ID 与版本怎么填') }}</th></tr></thead><tbody>
      <tr><td>{{ t('New request', '新的一次请求') }}</td><td>{{ t('New eventId (36 characters), new objectIds; related steps share a new traceId. Omit revision.', '生成新的 36 字符 eventId 和各自的新 objectId；请求与步骤共用新的 traceId。revision 不填即可。') }}</td></tr>
      <tr><td>{{ t('Network retry', '网络重试') }}</td><td>{{ t('Resend the same IDs and content unchanged.', '保留同一份 ID 和内容原样重发，不要重新生成 ID。') }}</td></tr>
      <tr><td>{{ t('Update a record', '更新原记录（进阶）') }}</td><td>{{ t('Keep objectId, generate a new eventId and send a higher revision with the complete replacement record. This is not a request stage or status.', '保留 objectId，生成新 eventId，显式填写更高 revision 并提交完整替换记录。版本号不代表请求阶段、完成状态或对错。') }}</td></tr>
    </tbody></table>
    <div class="code-box">
      <div>Body · JSON</div>
      <pre>{{ minimalBody }}</pre>
    </div>
    <h3>Resp</h3>
    <p>{{ t('Invalid event parameters return HTTP 400 immediately with errors[].index, field and code; the whole batch is rejected without a batchId or unit charge. A valid batch receives the HTTP 200 receipt below; state-dependent checks continue afterwards.', '事件参数不合法会直接返回 HTTP 400，包含 errors[].index、field 和 code；整批不接收，不生成 batchId、不扣 units。格式正确后返回下方 HTTP 200 回执，依赖当前状态的检查仍会继续执行。') }}</p>
    <p>
      {{
        t(
          "HTTP 200 means the batch was received. The upload response always has status pending; this is a fixed receipt, not live progress. If needed, query GET https://metertrail.com/v1/batches/{batchId} with the same project API key.",
          "HTTP 200 表示批次已接收。上报响应的 status 固定为 pending，不是实时处理进度。如需实时状态，使用同一项目密钥调用 GET https://metertrail.com/v1/batches/{batchId}。",
        )
      }}
    </p>
    <p><RouterLink :to="{ path: '/docs', hash: '#batch-status' }">{{ t("Query upload status documentation →", "查看查询上报状态接口 →") }}</RouterLink></p>
    <div class="code-box">
      <div>HTTP 200 · JSON</div>
      <pre>{{ minimalReceiptExample }}</pre>
    </div>
    <p>
      <RouterLink :to="{ path: '/docs', hash: '#fields' }">{{
        t(
          "View ingestion Header and Body fields →",
          "查看 Header、Body 接入字段 →",
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
{{ requestHeaders }}</pre>
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
        <div>HTTP 200 · JSON</div>
        <pre>{{ receiptExample }}</pre>
      </div>
    </details>
  </div>
</template>
