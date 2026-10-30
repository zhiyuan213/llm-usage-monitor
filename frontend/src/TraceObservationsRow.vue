<script setup lang="ts">
import {ref,onMounted,onBeforeUnmount,watch} from 'vue';
import {api,t,n,money,date,errorText} from './lib';
import {observationScore} from './trace-score';
import {traceColumnWidths,observationTokens} from './trace-column-layout';
const props=defineProps<{project:string;traceId:string;editing?:boolean}>();
const emit=defineEmits<{saved:[]}>();
const drafts=ref<Record<string,string>>({}),saving=ref(false),saveError=ref('');
watch(()=>props.editing,()=>{saveError.value='';drafts.value=Object.fromEntries(rows.value.map(o=>[o.object_id,o.cost==null?'':String(o.cost)]));});
async function save(){
 if(busy.value||saving.value||error.value)return;
 saveError.value='';
 const changes=[];
 for(const o of rows.value){const value=drafts.value[o.object_id]??'';if(value===(o.cost==null?'':String(o.cost)))continue;
 if(!/^\d+(?:\.\d{1,12})?$/.test(value)||!Number.isFinite(Number(value))){saveError.value=t('Enter a nonnegative USD amount with at most 12 decimal places.','请输入非负美元金额，最多 12 位小数。');return;}
 changes.push({objectId:o.object_id,revision:o.revision,expectedCost:o.cost,cost:Number(value)});}
 saving.value=true;try{if(changes.length)await api('/projects/'+props.project+'/traces/'+props.traceId+'/costs',{changes});emit('saved');await load();}catch(e){saveError.value=errorText(e);}finally{saving.value=false;}
}
defineExpose({save,saving});
const rows=ref<any[]>([]),busy=ref(true),error=ref('');let alive=true;
onBeforeUnmount(()=>{alive=false;});
async function load(){busy.value=true;error.value='';try{const result=await api('/projects/'+props.project+'/traces/'+props.traceId);if(alive)rows.value=result.objects.filter((o:any)=>o.type==='observation').map((o:any)=>({...o,score:observationScore(result.objects,o.object_id)}));if(alive)drafts.value=Object.fromEntries(rows.value.map(o=>[o.object_id,o.cost==null?'':String(o.cost)]));}catch(e){if(alive)error.value=errorText(e);}finally{if(alive)busy.value=false;}}
onMounted(load);
</script>
<template>
 <tr class="trace-observations"><td colspan="12">
  <section class="observation-group" :aria-label="t('Observations','调用步骤')">
   <header class="observation-group-heading"><span class="observation-group-mark">↳</span><strong>Observation</strong><span>{{t('Call steps','调用步骤')}}</span><span v-if="!busy&&!error" class="observation-count">{{rows.length}}</span></header>
   <p v-if="editing" class="observation-feedback">{{t('Amounts are USD. Blank unknown values stay unknown. Corrections apply to this revision; a newer reported revision replaces them. Token usage and units are unchanged.','费用单位为美元；空白未知值保持未知。修正针对当前版本，更高版本重新上报会替换；不修改 Token 或 units。')}}</p><p v-if="busy" class="observation-feedback" role="status">{{t('Loading observations…','正在加载 Observation…')}}</p>
   <p v-else-if="error" class="observation-feedback" role="alert">{{error}} <button class="btn" @click="load">{{t('Retry','重试')}}</button></p>
   <div v-else><p v-if="saveError" class="observation-feedback" role="alert">{{saveError}}</p><div class="table-wrap"><table class="observation-table"><colgroup><col v-for="(width,i) in traceColumnWidths" :key="i" :style="{width:width+'%'}" /></colgroup><thead><tr><th aria-hidden="true"></th><th>{{t('Model','模型名称')}}</th><th>eventId</th><th>{{t('Object ID','对象 ID')}}</th><th class="numeric">{{t('Duration','耗时')}}</th><th class="numeric">Token</th><th class="numeric">{{t('Estimated cost','估算费用')}}</th><th>{{t('Provider','供应商')}}</th><th>{{t('Status','状态')}}</th><th>{{t('Score','打分')}}</th><th>{{t('Started at (UTC)','开始时间（UTC）')}}</th><th aria-hidden="true"></th></tr></thead>
   <tbody><tr v-for="o in rows" :key="o.object_id"><td aria-hidden="true"></td><td><strong class="observation-model">{{o.data.model||'—'}}</strong></td><td><code>{{o.event_id||'—'}}</code></td><td><code>{{o.object_id}}</code></td><td class="numeric">{{n(o.data.durationMs)}}<span class="observation-unit"> ms</span></td><td class="numeric">{{n(observationTokens(o))}}</td><td class="numeric"><input v-if="editing" v-model="drafts[o.object_id]" class="cost-correction-input" inputmode="decimal" :disabled="saving" :aria-label="t('Cost in USD for ','美元费用：')+o.object_id" :placeholder="t('Unknown','未知')" /><template v-else>{{money(o.cost)}}<small v-if="o.applied_rates?.manual">{{t('Corrected','人工修正')}}</small></template></td><td>{{o.data.provider||'—'}}</td><td><span class="badge" :class="o.data.status==='success'?'success':o.data.status==='error'?'danger':''">{{o.data.status||'—'}}</span></td><td :title="o.score.reported?t('Latest Score reported for this Observation','关联当前 Observation 的最新 Score 评分'):t('No Score reported; displaying 0','未上报 Score，默认显示 0')">{{o.score.value}}</td><td class="observation-time">{{date(o.started_at)}}</td><td aria-hidden="true"></td></tr>
   <tr v-if="!rows.length"><td colspan="12" class="empty-cell">{{t('No retained observations.','暂无保留的 Observation。')}}</td></tr></tbody></table></div></div>
  </section>
 </td></tr>
</template>
