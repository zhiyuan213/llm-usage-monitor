<script setup lang="ts">
import {t} from './lib';
defineProps<{selected:string}>();
</script>
<template>
 <section class="scenario-flow" :aria-label="selected==='multiple'?t('Upload order','上报顺序'):t('Object relationships, not upload order','对象关联，非上报顺序')">
  <strong>{{t('One application request: document analysis','一笔业务请求：文档分析')}}</strong>
  <template v-if="selected==='multiple'">
   <p>{{t('Upload order · send each step after it completes','上报顺序 · 每个步骤完成后发送')}}</p>
   <ol class="scenario-upload-steps">
    <li><strong>{{t('First upload','第1次上报')}}</strong><div><span class="scenario-type">Observation 1</span><span>{{t('Extract the table · 3,000 tokens / 2 seconds','抽取表格 · 3,000 Token / 2 秒')}}</span></div></li>
    <li><strong>{{t('Second upload','第2次上报')}}</strong><div><span class="scenario-type">Observation 2</span><span>{{t('Calculate the ratio · 1,200 tokens / 1 second','计算比率 · 1,200 Token / 1 秒')}}</span></div></li>
    <li><strong>{{t('Final upload · same batch','最后一次上报 · 同一批次')}}</strong><div><span class="scenario-type">Observation 3 + Trace</span><span>{{t('Write the conclusion · 2,500 tokens / 3 seconds; summarize the whole request with its final status and total duration.','撰写结论 · 2,500 Token / 3 秒；同时汇总整次请求的最终状态和总耗时。')}}</span></div></li>
   </ol>
   <p>{{t('All three uploads share traceId. Trace is the final request summary; startedAt remains the actual request start time.','三次上报共用 traceId。Trace 是整次请求结束后的汇总，startedAt 仍填请求真实开始时间。')}}</p>
  </template>
  <template v-else>
   <p>{{t('Object relationships · this is not an upload sequence','对象关联 · 不表示上报先后')}}</p>
   <div class="scenario-flow-row"><span class="scenario-type">Trace</span><span>{{t('Whole request · total time, status, user and customer','整次请求 · 总耗时、状态、用户与客户')}}</span></div>
   <div class="scenario-flow-children">
    <div class="scenario-flow-row"><span class="scenario-type">Observation 1</span><span>{{t('Model call · 1,900 tokens / 2 seconds','模型调用 · 1,900 Token / 2 秒')}}</span></div>
    <div v-if="selected.startsWith('score')" class="scenario-flow-row"><span class="scenario-type">Score</span><span>{{selected==='score-trace'?t('Whole-request feedback → Trace objectId','整次请求的评价 → Trace 的 objectId'):t('Step feedback → Observation 1 objectId','某次模型调用的评价 → Observation 1 的 objectId')}}</span></div>
   </div>
   <p>{{t('Trace and Observations share traceId; Score links through targetType + targetId.','Trace 和 Observation 共用 traceId；Score 通过 targetType + targetId 关联。')}}</p>
  </template>
 </section>
</template>
