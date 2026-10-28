<script setup lang="ts">
import IntegrationScenarios from "./IntegrationScenarios.vue";
import { t } from "./lib";
import {
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
    <h3>{{ t('Choose your reporting scenario', '选择你的上报场景') }}</h3>
    <IntegrationScenarios />
    <p>{{t('Model and Token columns come from generation Observations linked by traceId. Report actual provider, model and usage. Configure matching model prices before ingestion; missing prices remain unknown, and adding a price does not automatically reprice old records.', '模型与 Token 来自同一 traceId 下的 generation Observation。请上报实际供应商、模型与用量，接入前配置匹配的模型价格；缺少价格显示未知，补充价格不会自动重算历史记录。')}}</p>
    <h3>Resp</h3>
    <p>{{ t('Invalid event parameters return HTTP 400 immediately with errors[].index, field and code; the whole batch is rejected without a batchId or unit charge. A valid batch receives the HTTP 202 receipt below; state-dependent checks continue afterwards.', '事件参数不合法会直接返回 HTTP 400，包含 errors[].index、field 和 code；整批不接收，不生成 batchId、不扣 units。格式正确后返回下方 HTTP 202 回执，依赖当前状态的检查仍会继续执行。') }}</p>
    <p>
      {{
        t(
          "HTTP 202 means the batch was received. The upload response always has status pending; this is a fixed receipt, not live progress. If needed, query GET https://metertrail.com/v1/batches/{batchId} with the same project API key.",
          "HTTP 202 表示批次已接收。上报响应的 status 固定为 pending，不是实时处理进度。如需实时状态，使用同一项目密钥调用 GET https://metertrail.com/v1/batches/{batchId}。",
        )
      }}
    </p>
    <p><RouterLink :to="{ path: '/docs', hash: '#batch-status' }">{{ t("Query upload status documentation →", "查看查询上报状态接口 →") }}</RouterLink></p>
    <div class="code-box">
      <div>HTTP 202 · JSON</div>
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

  </div>
</template>
