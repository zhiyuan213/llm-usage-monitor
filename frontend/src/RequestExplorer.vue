<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue';
import {api,t,n,money,date,language,errorText} from './lib';
import QueryControls from './QueryControls.vue';
import {usePageSize} from './page-size';
import {requestSummary,stepWidth} from './request-explorer';
const props=defineProps<{project:string;window:{from:string;to:string};refreshToken:boolean}>();
const filter=ref('all'),page=ref(0),size=usePageSize(),total=ref(0),items=ref<any[]>([]),selected=ref<any>(null),objects=ref<any[]>([]);
const busy=ref(false),detailBusy=ref(false),error=ref(''),detailError=ref('');
let listEpoch=0,detailEpoch=0,alive=true;
onBeforeUnmount(()=>{alive=false;++listEpoch;++detailEpoch;});
const query=computed(()=>new URLSearchParams({project:props.project,...props.window,status:filter.value==='error'?'error':'',...(filter.value==='slow'?{minDurationMs:'5000'}:{}),sort:'started_at',direction:'desc'}).toString());
const summary=computed(()=>requestSummary(objects.value));
const steps=computed(()=>objects.value.filter(o=>o.type==='observation'));
const scores=computed(()=>objects.value.filter(o=>o.type==='score'));
async function choose(row:any){
 const id=++detailEpoch;selected.value=row;objects.value=[];detailError.value='';detailBusy.value=true;
 try {const result=await api('/projects/'+props.project+'/traces/'+row.trace_id);if(alive&&id===detailEpoch)objects.value=result.objects;}
 catch(e){if(alive&&id===detailEpoch)detailError.value=errorText(e);}
 finally{if(alive&&id===detailEpoch)detailBusy.value=false;}
}
async function load(reset=false){
 if(reset)page.value=0;
 const id=++listEpoch;++detailEpoch;selected.value=null;objects.value=[];detailBusy.value=false;busy.value=true;error.value='';items.value=[];
 try {const result=await api('/reports/traces?'+query.value+'&page='+page.value+'&size='+size.value);
 if(!alive||id!==listEpoch)return;
 total.value=result.total;
 if(page.value>0&&page.value*size.value>=total.value){page.value=Math.max(0,Math.ceil(total.value/size.value)-1);void load();return;}
 items.value=result.items;if(items.value.length)void choose(items.value[0]);
 }catch(e){if(alive&&id===listEpoch){total.value=0;error.value=errorText(e);}}
 finally{if(alive&&id===listEpoch)busy.value=false;}
}
function setFilter(value:string){filter.value=value;void load(true);}
function turn(value:number){page.value=value;void load();}
watch(()=>[props.project,props.window.from,props.window.to],()=>void load(true),{immediate:true});
watch(()=>props.refreshToken,(value,old)=>{if(old&&!value)void load();});
</script>
<template>
<div class="request-explorer">
 <section class="panel request-list"><div class="panel-heading"><h2>{{t('Explore requests','查看请求')}}</h2><div class="query-export"><a class="btn" :href="'/api/reports/traces/export?'+query+'&format=xlsx&lang='+language">{{t('Export Excel','导出 Excel')}}</a><a class="btn" :href="'/api/reports/traces/export?'+query+'&format=pdf&lang='+language">{{t('Export PDF','导出 PDF')}}</a></div></div>
 <div class="request-filters"><button v-for="f in [{id:'all',en:'All',zh:'全部'},{id:'slow',en:'Slow requests (≥ 5 s)',zh:'慢请求（≥ 5 秒）'},{id:'error',en:'Failed requests',zh:'失败请求'}]" :key="f.id" class="btn" :class="{primary:filter===f.id}" :aria-pressed="filter===f.id" @click="setFilter(f.id)">{{t(f.en,f.zh)}}</button></div>
 <p v-if="busy" role="status">{{t('Loading requests…','正在加载请求…')}}</p><div v-else-if="error" role="alert">{{error}} <button class="btn" @click="load()">{{t('Retry','重试')}}</button></div>
 <div v-else class="request-cards"><button v-for="row in items" :key="row.object_id" class="request-card" :class="{selected:selected?.object_id===row.object_id}" :aria-pressed="selected?.object_id===row.object_id" @click="choose(row)"><span><strong>{{row.data.name||row.object_id}}</strong><small>{{row.data.feature||t('No feature','未提供功能')}} · {{row.models||t('Model not reported','未上报模型')}}</small><small>{{date(row.started_at)}}</small></span><span>{{n(row.data.durationMs)}} ms<small>{{row.data.status==='success'?t('Success','成功'):row.data.status==='error'?t('Failed','失败'):row.data.status||'—'}}</small></span><span>{{money(row.estimated_cost)}}<small v-if="Number(row.unknown_costs)>0">{{t('Partial or unknown cost','费用不完整或未知')}}</small></span></button><p v-if="!items.length" class="empty-cell">{{t('No requests in this range.','该时间范围内暂无请求。')}}</p></div>
 <QueryControls :page="page" :size="size" :total="total" :loading="busy" @change="turn" @resize="size=$event;load(true)" />
 </section>
 <section class="panel request-detail" aria-live="polite"><template v-if="selected"><span class="badge">{{t('Request details','请求详情')}}</span><h2>{{selected.data.name||selected.object_id}}</h2><p v-if="detailBusy" role="status">{{t('Loading steps…','正在加载调用步骤…')}}</p><div v-else-if="detailError" role="alert">{{detailError}} <button class="btn" @click="choose(selected)">{{t('Retry','重试')}}</button></div><template v-else>
 <dl><div><dt>{{t('Model','模型')}}</dt><dd>{{summary.models||t('Not reported','未上报')}}</dd></div><div><dt>{{t('Input / output tokens','输入 / 输出 Token')}}</dt><dd>{{n(summary.input)}} / {{n(summary.output)}}</dd></div><div><dt>{{t('Known estimated cost','已知估算费用')}}</dt><dd>{{money(summary.cost)}}<small>{{summary.priced}}/{{summary.calls}} {{t('model calls priced','条模型调用已定价')}}</small></dd></div></dl>
 <h3>{{t('Where the time went','耗时分布')}}</h3><ol class="request-steps"><li v-for="step in steps" :key="step.object_id"><div><strong>{{step.data.name||step.data.model||step.data.observationType||step.object_id}}</strong><span>{{n(step.data.durationMs)}} ms</span></div><div class="request-step-track"><span :style="{width:stepWidth(step.data.durationMs,selected.data.durationMs)+'%'}"></span></div></li></ol><p v-if="!steps.length">{{t('No retained steps were reported for this request.','该请求暂无保留的调用步骤。')}}</p>
 <div v-if="scores.length" class="request-scores"><h3>{{t('Scores','评分')}}</h3><p v-for="score in scores" :key="score.object_id">{{score.data.name}}: {{score.data.value}} <small>{{objects.find(o=>o.type===score.data.targetType&&o.object_id===score.data.targetId)?.data.name||score.data.targetId}}</small></p></div>
 <RouterLink class="request-full" :to="'/app/trace/'+selected.trace_id+'?project='+project">{{t('View full request →','查看完整请求 →')}}</RouterLink></template></template><p v-else>{{t('Select a request to see its steps.','选择一条请求查看调用步骤。')}}</p></section>
</div>
</template>
