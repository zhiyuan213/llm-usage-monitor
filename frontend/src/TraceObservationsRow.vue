<script setup lang="ts">
import {ref,onMounted,onBeforeUnmount} from 'vue';
import {api,t,n,money,date,errorText} from './lib';
import {traceColumnWidths,observationTokens} from './trace-column-layout';
const props=defineProps<{project:string;traceId:string}>();
const rows=ref<any[]>([]),busy=ref(true),error=ref('');let alive=true;
onBeforeUnmount(()=>{alive=false;});
async function load(){busy.value=true;error.value='';try{const result=await api('/projects/'+props.project+'/traces/'+props.traceId);if(alive)rows.value=result.objects.filter((o:any)=>o.type==='observation');}catch(e){if(alive)error.value=errorText(e);}finally{if(alive)busy.value=false;}}
onMounted(load);
</script>
<template>
 <tr class="trace-observations"><td colspan="12">
  <section class="observation-group" :aria-label="t('Observations','调用步骤')">
   <header class="observation-group-heading"><span class="observation-group-mark">↳</span><strong>Observation</strong><span>{{t('Call steps','调用步骤')}}</span><span v-if="!busy&&!error" class="observation-count">{{rows.length}}</span></header>
   <p v-if="busy" class="observation-feedback" role="status">{{t('Loading observations…','正在加载 Observation…')}}</p>
   <p v-else-if="error" class="observation-feedback" role="alert">{{error}} <button class="btn" @click="load">{{t('Retry','重试')}}</button></p>
   <div v-else class="table-wrap"><table class="observation-table"><colgroup><col v-for="(width,i) in traceColumnWidths" :key="i" :style="{width:width+'%'}" /></colgroup><thead><tr><th aria-hidden="true"></th><th>{{t('Model','模型名称')}}</th><th>eventId</th><th>{{t('Object ID','对象 ID')}}</th><th class="numeric">{{t('Duration','耗时')}}</th><th class="numeric">Token</th><th class="numeric">{{t('Estimated cost','估算费用')}}</th><th>{{t('Provider','供应商')}}</th><th>{{t('Status','状态')}}</th><th aria-hidden="true"></th><th>{{t('Started at (UTC)','开始时间（UTC）')}}</th><th aria-hidden="true"></th></tr></thead>
   <tbody><tr v-for="o in rows" :key="o.object_id"><td aria-hidden="true"></td><td><strong class="observation-model">{{o.data.model||'—'}}</strong></td><td><code>{{o.event_id||'—'}}</code></td><td><code>{{o.object_id}}</code></td><td class="numeric">{{n(o.data.durationMs)}}<span class="observation-unit"> ms</span></td><td class="numeric">{{n(observationTokens(o))}}</td><td class="numeric">{{money(o.cost)}}</td><td>{{o.data.provider||'—'}}</td><td><span class="badge" :class="o.data.status==='success'?'success':o.data.status==='error'?'danger':''">{{o.data.status||'—'}}</span></td><td aria-hidden="true"></td><td class="observation-time">{{date(o.started_at)}}</td><td aria-hidden="true"></td></tr>
   <tr v-if="!rows.length"><td colspan="12" class="empty-cell">{{t('No retained observations.','暂无保留的 Observation。')}}</td></tr></tbody></table></div>
  </section>
 </td></tr>
</template>
