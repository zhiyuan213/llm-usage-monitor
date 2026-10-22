<script setup lang="ts">
import { t } from "./lib";
import {
  batchIdExample,
  queryUrl,
  queryMethod,
  queryHeaders,
  queryRespExample,
  queryStates,
} from "./batch-query-reference";
</script>
<template>
  <div class="protocol-reference">
    <div class="api-endpoint">
      <p>
        <strong>URL：</strong><code>{{ queryUrl }}</code>
      </p>
      <p>
        <strong>Method：</strong><code>{{ queryMethod }}</code>
      </p>
    </div>
    <p>
      {{
        t(
          "Replace {batchId} with the batchId from the upload receipt. Use the API key for the same project.",
          "将 URL 中的 {batchId} 替换为上报回执中的 batchId，使用同一项目的 API 密钥。",
        )
      }}
    </p>
    <h3>Header</h3>
    <div class="code-box">
      <div>{{ t("Request headers", "请求头示例") }}</div>
      <pre>{{ queryHeaders }}</pre>
    </div>
    <p>
      {{
        t(
          "Host is generated from the URL. Set Authorization; this GET request has no JSON body and does not require Content-Type.",
          "Host 由客户端根据 URL 自动生成。设置 Authorization 即可；该 GET 请求没有 JSON 请求体，不要求设置 Content-Type。",
        )
      }}
    </p>
    <h3>Body</h3>
    <p>
      {{
        t(
          "No request body. batchId is a path parameter, not a Body parameter.",
          "无请求体。batchId 是 URL 路径参数，不放在 Body 中。",
        )
      }}
    </p>
    <div class="code-box">
      <div>{{ t("Example request", "请求示例") }}</div>
      <pre>
GET https://metertrail.com/v1/batches/{{ batchIdExample }}
Authorization: Bearer lm_live_&lt;YOUR_API_KEY&gt;</pre>
    </div>
    <h3>Resp</h3>
    <div class="code-box">
      <div>HTTP 200 · JSON</div>
      <pre>{{ queryRespExample }}</pre>
    </div>
    <p>
      {{
        t(
          "HTTP 200 means the query succeeded. Unlike the fixed pending value in an upload receipt, this status is the processing state at query time. completed does not guarantee every event succeeded; inspect rejected and errors.",
          "HTTP 200 表示查询成功。这里的 status 是查询时的实时处理状态，与上报回执固定返回的 pending 不同。completed 不代表每条数据成功，需结合 rejected 和 errors 判断。",
        )
      }}
    </p>
    <h3>{{ t("Status values", "status 取值") }}</h3>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>status</th>
            <th>{{ t("Meaning", "含义") }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in queryStates" :key="row[0]">
            <td>
              <code>{{ row[0] }}</code>
            </td>
            <td>{{ t(row[1], row[2]) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
