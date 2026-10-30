<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue';
import {api,t,n,money,date,errorText} from './lib';
import QueryControls from './QueryControls.vue';
import {requestSummary,stepWidth,costFormula} from './request-explorer';
import {traceObjectLabel} from './trace-object-label';
const props=defineProps<{project:string;window:{from:string;to:string};refreshToken:boolean}>();
const filter=ref('all'),page=ref(0),size=ref(10),total=ref(0),items=ref<any[]>([]),selected=ref<any>(null),objects=ref<any[]>([]);
const formulaOpen=ref(false);
const busy=ref(false),detailBusy=ref(false),error=ref(''),detailError=ref('');
let listEpoch=0,detailEpoch=0,alive=true;
onBeforeUnmount(()=>{alive=false;++listEpoch;++detailEpoch;});
const query=computed(()=>new URLSearchParams({project:props.project,...props.window,observation:filter.value==='all'?'':filter.value,sort:'started_at',direction:'desc'}).toString());
const summary=computed(()=>requestSummary(objects.value));
const steps=computed(()=>objects.value.filter(o=>o.type==='observation'));
const formulaCalls=computed(()=>objects.value.filter(o=>o.type==='observation'&&o.data.observationType==='generation'));
const scores=computed(()=>objects.value.filter(o=>o.type==='score'));
async function choose(row:any){
 const id=++detailEpoch;formulaOpen.value=false;selected.value=row;objects.value=[];detailError.value='';detailBusy.value=true;
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
defineExpose({setFilter});
function turn(value:number){page.value=value;void load();}
watch(()=>[props.project,props.window.from,props.window.to],()=>void load(true),{immediate:true});
watch(()=>props.refreshToken,(value,old)=>{if(old&&!value)void load();});
</script>
<template>
<div class="request-explorer">
 <section id="overview-requests" class="panel request-list">
 <div class="request-filters"><button v-for="f in [{id:'all',en:'All',zh:'全部'},{id:'slow',en:'Slow calls (≥ 5 s)',zh:'慢调用（≥ 5 秒）'},{id:'error',en:'Failed calls',zh:'失败调用'}]" :key="f.id" class="btn" :class="{primary:filter===f.id}" :aria-pressed="filter===f.id" @click="setFilter(f.id)">{{t(f.en,f.zh)}}</button><RouterLink class="btn request-view-more" :to="{path:'/app/traces',query:{project:props.project,...props.window}}">{{t('View more','查看更多')}}</RouterLink></div>
 <p v-if="busy" role="status">{{t('Loading requests…','正在加载请求…')}}</p><div v-else-if="error" role="alert">{{error}} <button class="btn" @click="load()">{{t('Retry','重试')}}</button></div>
 <div v-else class="request-cards"><button v-for="row in items" :key="row.object_id" class="request-card" :class="{selected:selected?.object_id===row.object_id}" :aria-pressed="selected?.object_id===row.object_id" @click="choose(row)"><span><strong>{{row.data.name||row.object_id}}</strong><small>{{date(row.started_at)}}</small></span><span>{{row.data.status==='success'?t('Success','成功'):row.data.status==='error'?t('Failed','失败'):row.data.status||'—'}}</span></button><p v-if="!items.length" class="empty-cell">{{t('No requests in this range.','该时间范围内暂无请求。')}}</p></div>
 <QueryControls :page="page" :size="size" :total="total" :loading="busy" @change="turn" @resize="size=$event;load(true)" />
 </section>
 <section class="panel request-detail" aria-live="polite"><template v-if="selected"><span class="badge">{{t('Request details','请求详情')}}</span><h2>{{selected.data.name||selected.object_id}}</h2><p v-if="detailBusy" role="status">{{t('Loading steps…','正在加载调用步骤…')}}</p><div v-else-if="detailError" role="alert">{{detailError}} <button class="btn" @click="choose(selected)">{{t('Retry','重试')}}</button></div><template v-else>
 <dl><div><dt>{{t('Request duration','请求总耗时')}}</dt><dd>{{n(selected.data.durationMs)}} ms</dd></div><div><dt>{{t('Input / output / cached tokens','输入 / 输出 / 缓存 Token')}}</dt><dd>{{n(summary.input)}} / {{n(summary.output)}} / {{n(summary.cached)}}</dd></div><div><dt>{{t('Known estimated cost','已知估算费用')}}</dt><dd>{{money(summary.cost)}}<span v-if="formulaCalls.length" class="cost-help" :class="{open:formulaOpen}" @keydown.esc="formulaOpen=false"><button type="button" class="cost-help-button" :aria-label="t('Show cost calculation','查看计算公式')" aria-describedby="request-cost-tooltip" :aria-expanded="formulaOpen" @click="formulaOpen=!formulaOpen">?</button><aside id="request-cost-tooltip" role="tooltip" class="request-cost-tooltip"><h3>{{t('Current cost calculation','当前结果计算公式')}}</h3><p>{{t('Rates are USD per million tokens; cached tokens are included in input and charged separately. Each call uses its recorded rates.','单价单位：美元/百万 Token；缓存包含在输入中，扣除后单独计费。每次调用使用当时记录的实际费率。')}}</p><ol><li v-for="call in formulaCalls" :key="call.object_id"><span class="cost-formula-call"><strong>{{traceObjectLabel(call)}}</strong><code>{{call.object_id}}</code></span><p>{{costFormula(call)||t('Unknown usage or price; excluded from known cost.','用量或价格未知，不计入已知费用。')}}</p></li></ol><p>{{t('Sum of known call costs','已知调用费用合计')}} = {{money(summary.cost)}}</p></aside></span></dd></div></dl>

 <h3>{{t('Where the time went' ,'耗时分布')}}</h3><ol class="request-steps"><li v-for="step in steps" :key="step.object_id"><div><strong>{{traceObjectLabel(step)}}</strong><span>{{n(step.data.durationMs)}} ms</span></div><code class="waterfall-object-id">{{step.object_id}}</code><div class="request-step-track"><span :style="{width:stepWidth(step.data.durationMs,selected.data.durationMs)+'%'}"></span></div></li></ol><p v-if="!steps.length">{{t('No retained steps were reported for this request.','该请求暂无保留的调用步骤。')}}</p>
 <div v-if="scores.length" class="request-scores"><h3>{{t('Scores','评分')}}</h3><p v-for="score in scores" :key="score.object_id">{{score.data.name}}: {{score.data.value}} <small>{{objects.find(o=>o.type===score.data.targetType&&o.object_id===score.data.targetId)?.data.name||score.data.targetId}}</small></p></div>
 <RouterLink class="request-full" :to="'/app/trace/'+selected.trace_id+'?project='+project">{{t('View full request','查看完整请求')}}</RouterLink></template></template><p v-else>{{t('Select a request to see its steps.','选择一条请求查看调用步骤。')}}</p></section>
</div>
</template>
