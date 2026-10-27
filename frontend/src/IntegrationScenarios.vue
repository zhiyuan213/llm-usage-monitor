<script setup lang="ts">
import { computed, ref } from 'vue';
import { t } from './lib';
import { scenarioSources, restScenarios } from './scenario-examples';
defineProps<{ sdk?: boolean }>();
const language = ref('Node.js');
const selected = ref('single');
const copied = ref('');
function choose(id:string) { if(id!==selected.value && !id.startsWith('score')) refresh(); selected.value=id; copied.value=''; }
const scenarios = [
  { id:'single', en:'1. One model call', zh:'1. 一次模型调用', units:2 },
  { id:'multiple', en:'2. Multiple model calls', zh:'2. 多次模型调用', units:4 },
  { id:'score-trace', en:'3. Score the whole request', zh:'3. 评价整次请求', units:1 },
  { id:'score', en:'4. Score one model call', zh:'4. 评价某次模型调用', units:1 },
];
const traceId = ref(crypto.randomUUID().replaceAll('-',''));
const timestamp = ref(new Date().toISOString());
function refresh() { traceId.value=crypto.randomUUID().replaceAll('-',''); timestamp.value=new Date().toISOString(); copied.value=''; }
const rest = computed(() => {
  return restScenarios[selected.value].map(events=>JSON.stringify({events:events.map(event=>({eventId:crypto.randomUUID(),...event}))},null,2)
    .replaceAll('@trace@',traceId.value).replaceAll('@time@',timestamp.value).replaceAll('@score@',crypto.randomUUID()));
});
async function copy(text:string,id:string) { try { await navigator.clipboard.writeText(text); copied.value=id; } catch { copied.value='failed'; } }
</script>
<template>
  <div class="integration-scenarios">
    <details><summary>{{t('ID rules: lengths, uniqueness and links','ID 规则：长度、唯一性与关联')}}</summary>
      <ul>
        <li>{{t('eventId: exactly 36 characters; unique per event within a project. Format is unrestricted. SDKs generate it when omitted. Keep the original ID and content on retry.','eventId：恰好 36 个字符，同一项目中每条新事件唯一，不限制格式；SDK 可自动生成。重试保留原 ID 和内容。')}}</li>
        <li>{{t('objectId: a nonblank string up to 128 UTF-8 bytes, unique within project + type. Identifies one specific request, step or score; not a feature name.','objectId：非空、最多 128 个 UTF-8 字节，同一项目和 type 内唯一；代表一条具体请求、步骤或评分，不是功能名称。')}}</li>
        <li>{{t('traceId: 32 hexadecimal characters. One request shares it across its Trace and Observations; use a new value for each new request.','traceId：32 个十六进制字符；一笔请求中的 Trace 与所有 Observation 共用，新请求使用新值。')}}</li>
        <li>{{t('spanId: 16 hexadecimal characters per Observation step. parentSpanId is the parent step spanId, or null/omitted for a root step.','spanId：每个 Observation 步骤使用不同的 16 位十六进制值；parentSpanId 填父步骤的 spanId，没有父步骤时省略或填 null。')}}</li>
        <li>{{t('Score targetId is the target objectId in the same project, selected by targetType (trace or observation). It is never traceId, eventId or spanId.','Score 的 targetId 填同一项目中目标的 objectId，由 targetType（trace 或 observation）决定；不是 traceId、eventId 或 spanId。')}}</li>
      </ul>
    </details>
    <p>{{t('Start with your application request. A Trace describes the whole request; each Observation describes one model call or step. A Score is optional feedback, not a required stage.', '从一笔业务请求理解接入：Trace 记录整次请求，Observation 记录其中一次模型调用或步骤。Score 是可选评分，不是必经阶段。')}}</p>
    <div class="scenario-tabs" role="group" :aria-label="t('Choose a scenario','选择接入场景')">
      <button v-for="item in scenarios" :key="item.id" class="btn" :class="{primary:selected===item.id}" :aria-pressed="selected===item.id" @click="choose(item.id)">{{t(item.en,item.zh)}}</button>
    </div>
    <p v-if="selected==='score-trace'"><code>targetType: trace → targetId: trace-{{traceId}}</code></p>
    <p v-if="selected==='score'"><code>targetType: observation → targetId: obs-{{traceId}}-1</code></p>
    <section class="scenario-flow" :aria-label="t('How the records connect','记录关联示意图')">
      <strong>{{t('One application request: document analysis','一笔业务请求：文档分析')}}</strong>
      <div class="scenario-flow-row"><span class="scenario-type">Trace</span><span>{{t('Whole request · total time, status, user and customer','整次请求 · 总耗时、状态、用户与客户')}}</span></div>
      <div class="scenario-flow-children">
        <div class="scenario-flow-row"><span class="scenario-type">Observation 1</span><span>{{selected==='multiple' ? t('Extract the table · 3,000 tokens / 2 seconds','抽取表格 · 3,000 Token / 2 秒') : t('Model call · 1,900 tokens / 2 seconds','模型调用 · 1,900 Token / 2 秒')}}</span></div>
        <template v-if="selected==='multiple'"><div class="scenario-flow-row"><span class="scenario-type">Observation 2</span><span>{{t('Calculate the ratio · 1,200 tokens / 1 second','计算比率 · 1,200 Token / 1 秒')}}</span></div><div class="scenario-flow-row"><span class="scenario-type">Observation 3</span><span>{{t('Write the conclusion · 2,500 tokens / 3 seconds','撰写结论 · 2,500 Token / 3 秒')}}</span></div></template>
        <div v-if="selected.startsWith('score')" class="scenario-flow-row"><span class="scenario-type">Score</span><span>{{selected==='score-trace'?t('Whole-request feedback → Trace objectId','整次请求的评价 → Trace 的 objectId'):t('Step feedback → Observation 1 objectId','某次模型调用的评价 → Observation 1 的 objectId')}}</span></div>
      </div>
      <p>{{t('Trace and Observations share traceId. Score links through targetType + targetId.','Trace 和 Observation 共用 traceId；Score 通过 targetType + targetId 关联。')}}</p>
    </section>
    <p v-if="selected==='single'">{{t('When the request completes, send Trace + Observation together in one batch: 1 request in the console, 2 new objects = 2 units. The sample totals are illustrative; replace them with measured values.', '请求结束后，将 Trace + Observation 放在一个批次一起发送。调用列表显示 1 笔请求，新建 2 个对象合计 2 units。示例数值仅作说明，请替换成实际测量值。')}}</p>
    <p v-else-if="selected==='multiple'">{{t('Send each completed model step separately. The final group sends Observation 3 + the final Trace. All groups share traceId: 1 application request, 4 new objects = 4 units. You may also send all four objects together after completion.', '每个模型步骤结束后可以分别发送，最后一组发送 Observation 3 + 整次请求的最终 Trace。各组共用 traceId：仍是 1 笔业务请求，新建 4 个对象合计 4 units。也可以等全部完成后，将四个对象一起发送。')}}</p>
    <p v-else-if="selected==='score-trace'">{{t('Score the whole request: targetType is trace and targetId is the earlier Trace objectId, not traceId. Send only a new Score (1 unit).','评价整次请求：targetType 填 trace，targetId 填之前 Trace 的 objectId，不是 traceId。单独发送新 Score（1 unit）。')}}</p>
    <p v-else>{{t('This is a separate request, sent only when feedback exists. Use the earlier Observation objectId as targetId. It adds 1 new Score = 1 unit; do not resend Trace or Observation. The score scale is defined by your application.', '有评价时才单独请求，targetId 填之前 Observation 的 objectId。新建 1 条 Score 消耗 1 unit，不用重发 Trace 或 Observation。评分范围由你的业务定义。')}}</p>
    <p v-if="selected==='score-trace'">{{t('For SDK examples, set METERTRAIL_TRACE_ID to the traceId printed earlier; this constructs the earlier Trace objectId as trace-<traceId>. For your own objects, use their actual objectId.','SDK 示例先将 METERTRAIL_TRACE_ID 设置为之前打印的 traceId，按示例命名构造之前的 Trace objectId（trace-<traceId>）；自己的数据请填真实 objectId。')}}</p>
    <template v-if="sdk">
      <label class="scenario-language">{{t('SDK language','SDK 语言')}} <select v-model="language"><option>Node.js</option><option>Python</option><option>Java</option></select></label>
      <p>{{t('Install the source SDK using the repository instructions above. Node/Python samples run from that repository root. For Java, save Scenario.java and add the SDK and its Jackson dependencies to your application classpath. Set METERTRAIL_ENDPOINT=https://metertrail.com and METERTRAIL_API_KEY locally; never put keys in frontend code.', '先按上方仓库说明安装源码 SDK。Node / Python 示例从 SDK 仓库根目录运行；Java 保存为 Scenario.java，在应用中加入 SDK 及其 Jackson 依赖。环境变量设置 METERTRAIL_ENDPOINT=https://metertrail.com、METERTRAIL_API_KEY，仅在本地服务端保存密钥。')}}</p>
      <p v-if="selected==='score'">{{t('Set METERTRAIL_TRACE_ID to the traceId printed by the earlier single/multiple example. The referenced Observation must already exist in the same project.', '将 METERTRAIL_TRACE_ID 设置为之前单次或多次调用示例打印的 traceId，确保目标 Observation 已在同一项目处理成功。')}}</p>
      <div class="code-box"><div>{{language}} · {{t(scenarios.find(s=>s.id===selected)!.en,scenarios.find(s=>s.id===selected)!.zh)}}<button @click="copy(scenarioSources[language][selected],'sdk')">{{copied==='sdk'?t('Copied','已复制'):t('Copy','复制')}}</button></div><pre>{{scenarioSources[language][selected]}}</pre></div>
      <p>{{t('record queues locally; it does not confirm server acceptance. flush sends buffered events and HTTP 200/pending only confirms receipt. Automatic batching, concurrent recording and retries can change network batch boundaries. Check batch results when needed; SDK buffers are in memory, not durable storage.', 'record 只加入本地队列，不表示服务器已接收；flush 发送缓冲数据，HTTP 200 / pending 也只表示收到批次。自动批处理、并发记录及重试可能改变实际网络分批边界。按需查询批次结果；SDK 为内存缓冲，不是持久队列。')}}</p>
    </template>
    <template v-else>
      <p>{{t('Each block below is a separate POST body for /v1/events/batch, with the same project Authorization header. schemaVersion, agent and revision are omitted to use defaults.', '下方每个代码块分别作为一次 POST /v1/events/batch 的 Body，使用同一项目的 Authorization 请求头。省略 schemaVersion、agent 和 revision，使用默认值。')}}</p>
      <button v-if="!selected.startsWith('score')" class="btn" @click="refresh">{{t('Generate new request IDs and time','生成新的请求 ID 和时间')}}</button>
      <p v-if="selected==='score'">{{t('Replace targetId with the Observation objectId you actually sent. This example targets Observation 1 in the other examples. Generate a new eventId and objectId for each new score.', 'targetId 请替换为实际已上报的 Observation objectId。本例指向其他场景中的 Observation 1；每条新评分应生成新的 eventId 与 objectId。')}}</p>
      <div v-for="(body,i) in rest" :key="`${selected}-${i}`" class="code-box"><div>{{t('Request','第')}} {{i+1}} {{t('· JSON','次请求 · JSON')}}<button @click="copy(body,String(i))">{{copied===String(i)?t('Copied','已复制'):t('Copy','复制')}}</button></div><pre>{{body}}</pre></div>
    </template>
    <p>{{t('For a new business request, use new objectIds, traceId and eventIds. Retries keep all IDs and content unchanged. SDKs generate eventId and default revision 1 for you. No revision updates are needed when only final results are reported.', '新业务请求使用新的 objectId、traceId 和 eventId；失败重试保留所有 ID 和内容。SDK 自动生成 eventId，revision 默认 1。只上报最终结果时，无需更新修订版本。')}}</p>
    <p v-if="copied==='failed'" role="status">{{t('Copy failed; select and copy the code manually.','复制失败，请手动选中代码复制。')}}</p>
  </div>
</template>
