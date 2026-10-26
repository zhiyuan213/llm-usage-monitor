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
