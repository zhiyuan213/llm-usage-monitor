<script setup lang="ts">
import {ref,onMounted,onBeforeUnmount} from 'vue';
import {api,t,n,money,date,errorText} from './lib';
const props=defineProps<{project:string;traceId:string}>();
const rows=ref<any[]>([]),busy=ref(true),error=ref('');let alive=true;
onBeforeUnmount(()=>{alive=false;});
async function load(){busy.value=true;error.value='';try{const result=await api('/projects/'+props.project+'/traces/'+props.traceId);if(alive)rows.value=result.objects.filter((o:any)=>o.type==='observation');}catch(e){if(alive)error.value=errorText(e);}finally{if(alive)busy.value=false;}}
onMounted(load);
</script>
<template><tr class="trace-observations"><td colspan="11"><p v-if="busy" role="status">{{t('Loading observations…','正在加载 Observation…')}}</p><p v-else-if="error" role="alert">{{error}} <button class="btn" @click="load">{{t('Retry','重试')}}</button></p><div v-else class="table-wrap"><table><thead><tr><th>eventId</th><th>objectId</th><th>{{t('Model','模型名称')}}</th><th>{{t('Duration','耗时')}}</th><th>{{t('Estimated cost','估算费用')}}</th><th>provider</th><th>status</th><th>startedAt (UTC)</th></tr></thead><tbody><tr v-for="o in rows" :key="o.object_id"><td><code>{{o.event_id||'—'}}</code></td><td><code>{{o.object_id}}</code></td><td>{{o.data.model||'—'}}</td><td>{{n(o.data.durationMs)}} ms</td><td>{{money(o.cost)}}</td><td>{{o.data.provider||'—'}}</td><td>{{o.data.status||'—'}}</td><td>{{date(o.started_at)}}</td></tr><tr v-if="!rows.length"><td colspan="8">{{t('No retained observations.','暂无保留的 Observation。')}}</td></tr></tbody></table></div></td></tr></template>
