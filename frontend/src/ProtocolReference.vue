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
