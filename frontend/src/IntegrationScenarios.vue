<script setup lang="ts">
import { computed, ref } from 'vue';
import { t } from './lib';
import { scenarioSources, restScenarios } from './scenario-examples';
defineProps<{ sdk?: boolean }>();
const language = ref('Node.js');
const selected = ref('single');
const copied = ref('');
function choose(id:string) { if(id!==selected.value && id!=='score') refresh(); selected.value=id; copied.value=''; }
const scenarios = [
  { id:'single', en:'1. One model call', zh:'1. 一次模型调用', units:2 },
  { id:'multiple', en:'2. Multiple model calls', zh:'2. 多次模型调用', units:4 },
  { id:'score', en:'Optional: send a score later', zh:'可选：稍后单独发送评分', units:1 },
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
    <p>{{t('Start with your application request. A Trace describes the whole request; each Observation describes one model call or step. A Score is optional feedback, not a required stage.', '从一笔业务请求理解接入：Trace 记录整次请求，Observation 记录其中一次模型调用或步骤。Score 是可选评分，不是必经阶段。')}}</p>
    <div class="scenario-tabs" role="group" :aria-label="t('Choose a scenario','选择接入场景')">
      <button v-for="item in scenarios" :key="item.id" class="btn" :class="{primary:selected===item.id}" :aria-pressed="selected===item.id" @click="choose(item.id)">{{t(item.en,item.zh)}}</button>
    </div>
    <section class="scenario-flow" :aria-label="t('How the records connect','记录关联示意图')">
      <strong>{{t('One application request: document analysis','一笔业务请求：文档分析')}}</strong>
      <div class="scenario-flow-row"><span class="scenario-type">Trace</span><span>{{t('Whole request · total time, status, user and customer','整次请求 · 总耗时、状态、用户与客户')}}</span></div>
      <div class="scenario-flow-children">
        <div class="scenario-flow-row"><span class="scenario-type">Observation 1</span><span>{{selected==='multiple' ? t('Extract the table · 3,000 tokens / 2 seconds','抽取表格 · 3,000 Token / 2 秒') : t('Model call · 1,900 tokens / 2 seconds','模型调用 · 1,900 Token / 2 秒')}}</span></div>
        <template v-if="selected==='multiple'"><div class="scenario-flow-row"><span class="scenario-type">Observation 2</span><span>{{t('Calculate the ratio · 1,200 tokens / 1 second','计算比率 · 1,200 Token / 1 秒')}}</span></div><div class="scenario-flow-row"><span class="scenario-type">Observation 3</span><span>{{t('Write the conclusion · 2,500 tokens / 3 seconds','撰写结论 · 2,500 Token / 3 秒')}}</span></div></template>
        <div v-if="selected==='score'" class="scenario-flow-row"><span class="scenario-type">Score</span><span>{{t('Later user feedback: 1 out of 5 · targets Observation 1','稍后的用户评价：满分 5 分给 1 分 · 指向 Observation 1')}}</span></div>
      </div>
      <p>{{t('Trace and Observations share traceId. Score links through targetType + targetId.','Trace 和 Observation 共用 traceId；Score 通过 targetType + targetId 关联。')}}</p>
    </section>
    <p v-if="selected==='single'">{{t('When the request completes, send Trace + Observation together in one batch: 1 request in the console, 2 new objects = 2 units. The sample totals are illustrative; replace them with measured values.', '请求结束后，将 Trace + Observation 放在一个批次一起发送。调用列表显示 1 笔请求，新建 2 个对象合计 2 units。示例数值仅作说明，请替换成实际测量值。')}}</p>
    <p v-else-if="selected==='multiple'">{{t('Send each completed model step separately. The final group sends Observation 3 + the final Trace. All groups share traceId: 1 application request, 4 new objects = 4 units. You may also send all four objects together after completion.', '每个模型步骤结束后可以分别发送，最后一组发送 Observation 3 + 整次请求的最终 Trace。各组共用 traceId：仍是 1 笔业务请求，新建 4 个对象合计 4 units。也可以等全部完成后，将四个对象一起发送。')}}</p>
    <p v-else>{{t('This is a separate request, sent only when feedback exists. Use the earlier Observation objectId as targetId. It adds 1 new Score = 1 unit; do not resend Trace or Observation. The score scale is defined by your application.', '有评价时才单独请求，targetId 填之前 Observation 的 objectId。新建 1 条 Score 消耗 1 unit，不用重发 Trace 或 Observation。评分范围由你的业务定义。')}}</p>
    <template v-if="sdk">
      <label class="scenario-language">{{t('SDK language','SDK 语言')}} <select v-model="language"><option>Node.js</option><option>Python</option><option>Java</option></select></label>
      <p>{{t('Install the source SDK using the repository instructions above. Node/Python samples run from that repository root. For Java, save Scenario.java and add the SDK and its Jackson dependencies to your application classpath. Set METERTRAIL_ENDPOINT=https://metertrail.com and METERTRAIL_API_KEY locally; never put keys in frontend code.', '先按上方仓库说明安装源码 SDK。Node / Python 示例从 SDK 仓库根目录运行；Java 保存为 Scenario.java，在应用中加入 SDK 及其 Jackson 依赖。环境变量设置 METERTRAIL_ENDPOINT=https://metertrail.com、METERTRAIL_API_KEY，仅在本地服务端保存密钥。')}}</p>
      <p v-if="selected==='score'">{{t('Set METERTRAIL_TRACE_ID to the traceId printed by the earlier single/multiple example. The referenced Observation must already exist in the same project.', '将 METERTRAIL_TRACE_ID 设置为之前单次或多次调用示例打印的 traceId，确保目标 Observation 已在同一项目处理成功。')}}</p>
      <div class="code-box"><div>{{language}} · {{t(scenarios.find(s=>s.id===selected)!.en,scenarios.find(s=>s.id===selected)!.zh)}}<button @click="copy(scenarioSources[language][selected],'sdk')">{{copied==='sdk'?t('Copied','已复制'):t('Copy','复制')}}</button></div><pre>{{scenarioSources[language][selected]}}</pre></div>
      <p>{{t('record queues locally; it does not confirm server acceptance. flush sends buffered events and HTTP 200/pending only confirms receipt. Automatic batching, concurrent recording and retries can change network batch boundaries. Check batch results when needed; SDK buffers are in memory, not durable storage.', 'record 只加入本地队列，不表示服务器已接收；flush 发送缓冲数据，HTTP 200 / pending 也只表示收到批次。自动批处理、并发记录及重试可能改变实际网络分批边界。按需查询批次结果；SDK 为内存缓冲，不是持久队列。')}}</p>
    </template>
    <template v-else>
      <p>{{t('Each block below is a separate POST body for /v1/events/batch, with the same project Authorization header. schemaVersion, agent and revision are omitted to use defaults.', '下方每个代码块分别作为一次 POST /v1/events/batch 的 Body，使用同一项目的 Authorization 请求头。省略 schemaVersion、agent 和 revision，使用默认值。')}}</p>
      <button v-if="selected!=='score'" class="btn" @click="refresh">{{t('Generate new request IDs and time','生成新的请求 ID 和时间')}}</button>
      <p v-if="selected==='score'">{{t('Replace targetId with the Observation objectId you actually sent. This example targets Observation 1 in the other examples. Generate a new eventId and objectId for each new score.', 'targetId 请替换为实际已上报的 Observation objectId。本例指向其他场景中的 Observation 1；每条新评分应生成新的 eventId 与 objectId。')}}</p>
      <div v-for="(body,i) in rest" :key="`${selected}-${i}`" class="code-box"><div>{{t('Request','第')}} {{i+1}} {{t('· JSON','次请求 · JSON')}}<button @click="copy(body,String(i))">{{copied===String(i)?t('Copied','已复制'):t('Copy','复制')}}</button></div><pre>{{body}}</pre></div>
    </template>
    <p>{{t('For a new business request, use new objectIds, traceId and eventIds. Retries keep all IDs and content unchanged. SDKs generate eventId and default revision 1 for you. No revision updates are needed when only final results are reported.', '新业务请求使用新的 objectId、traceId 和 eventId；失败重试保留所有 ID 和内容。SDK 自动生成 eventId，revision 默认 1。只上报最终结果时，无需更新修订版本。')}}</p>
    <p v-if="copied==='failed'" role="status">{{t('Copy failed; select and copy the code manually.','复制失败，请手动选中代码复制。')}}</p>
  </div>
</template>
