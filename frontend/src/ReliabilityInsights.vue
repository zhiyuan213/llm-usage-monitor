<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { api, ApiError, errorText, t, date, n, session } from './lib';
import QueryControls from './QueryControls.vue';
const props=defineProps<{project:string;refreshToken?:boolean}>();
const busy=ref(false),error=ref(''),locked=ref(false),data=ref<any>(null);
const days=ref(7),scope=ref('trace'),dimension=ref('feature'),view=ref('errors'),slowMs=ref(3000),page=ref(0),size=ref(5);
const groupPage=ref(0),groupSize=ref(5),errorPage=ref(0),errorSize=ref(5);
const custom=computed(()=>session.value?.workspace?.plan==='Pro');
const groups=computed(()=>(data.value?.groups||[]).slice(groupPage.value*groupSize.value,(groupPage.value+1)*groupSize.value));
const errors=computed(()=>(data.value?.error_groups||[]).slice(errorPage.value*errorSize.value,(errorPage.value+1)*errorSize.value));
const pct=(value:any)=>value==null?'—':`${n(Number(value)*100)}%`;
let epoch=0,alive=true;onBeforeUnmount(()=>{alive=false;++epoch;});
async function load(reset=false) {
  if(reset){page.value=0;groupPage.value=0;errorPage.value=0;}
  if(!custom.value)slowMs.value=3000;
  const id=++epoch;busy.value=true;error.value='';locked.value=false;
  try {
    const query=new URLSearchParams({days:String(days.value),scope:scope.value,dimension:dimension.value,view:view.value,slowMs:String(slowMs.value),page:String(page.value),size:String(size.value)});
    const result=await api(`/projects/${props.project}/insights/reliability?${query}`);
    if(!alive||id!==epoch)return;data.value=result;
    if(page.value>0&&page.value*size.value>=result.total){page.value=Math.max(0,Math.ceil(result.total/size.value)-1);await load();}
  }catch(e){if(alive&&id===epoch){data.value=null;locked.value=e instanceof ApiError&&e.code==='INSIGHTS_PLAN_REQUIRED';error.value=locked.value?'':errorText(e);}}
  finally{if(alive&&id===epoch)busy.value=false;}
}
watch(()=>props.project,()=>{data.value=null;void load(true);},{immediate:true});
watch(()=>props.refreshToken,(loading,old)=>{if(old&&!loading)void load();});
function groupLabel(label:any) {if(label==null)return t('Unassigned','未归类');if(data.value?.dimension==='model'){try{return JSON.parse(label).map((v:any)=>v||t('Unknown','未知')).join(' / ');}catch{return label;}}return label;}
const state=(value:string)=>({error:t('Failed','失败'),success:t('Success','成功'),cancelled:t('Cancelled','已取消')}[value]||value);
</script>
<template>
  <section class="business-insights" :aria-busy="busy">
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <section v-if="locked" class="panel"><h2>{{ t('Available with Core and Pro','Core 与 Pro 可使用') }}</h2><p>{{ t('Find frequent failures and slow calls in your application.','查找应用中反复发生的失败与慢调用。') }}</p><RouterLink class="btn primary" to="/app/billing">{{ t('View plans','查看套餐') }}</RouterLink></section>
    <template v-else>
      <section class="panel"><p>{{ t('Requests and model calls are analyzed separately. Missing durations are excluded from latency calculations; cancelled calls are not counted as failures.','应用请求与模型调用分开分析。未上报耗时的记录不参与耗时统计，取消的调用不算失败。') }}</p>
        <form class="insight-toolbar" @submit.prevent="load(true)">
          <label>{{ t('Period','统计区间') }}<select v-model.number="days" :disabled="busy"><option :value="1">{{ t('Last 24 hours','最近 24 小时') }}</option><option :value="7">{{ t('Last 7 days','最近 7 天') }}</option><option :value="14">{{ t('Last 14 days','最近 14 天') }}</option></select></label>
          <label>{{ t('Analyze','分析对象') }}<select v-model="scope" :disabled="busy"><option value="trace">{{ t('Application requests','应用请求') }}</option><option value="generation">{{ t('Model calls','模型调用') }}</option></select></label>
          <label>{{ t('Group by','分组') }}<select v-model="dimension" :disabled="busy"><option value="feature">{{ t('Feature','功能') }}</option><option value="model">{{ t('Model','模型') }}</option></select></label>
          <label>{{ t('Show calls','查看调用') }}<select v-model="view" :disabled="busy"><option value="errors">{{ t('Failed calls','失败调用') }}</option><option value="slow">{{ t('Slow calls','慢调用') }}</option></select></label>
          <label>{{ t('Slow at or above (ms)','慢调用阈值（毫秒）') }}<input v-model.number="slowMs" type="number" min="500" max="600000" step="1" :disabled="busy||!custom" required /></label>
          <button class="btn primary" :disabled="busy">{{ t('Analyze','开始分析') }}</button>
        </form><p v-if="!custom" class="muted">{{ t('Core uses a 3-second slow-call threshold. Pro can customize it from 500 to 600,000 ms.','Core 使用 3 秒慢调用阈值，Pro 可在 500～600,000 毫秒内自定义。') }}</p>
      </section>
      <p v-if="busy" role="status">{{ t('Loading…','正在加载…') }}</p>
      <template v-if="data">
        <div class="insight-metrics"><article class="panel"><span>{{ t('Reported calls','已上报调用') }}</span><strong>{{ n(data.summary.total) }}</strong></article><article class="panel"><span>{{ t('Failure rate','失败比例') }}</span><strong>{{ pct(data.summary.error_rate) }}</strong><small>{{ n(data.summary.errors) }} {{ t('failed','次失败') }}</small></article><article class="panel"><span>{{ t('Slow calls','慢调用') }}</span><strong>{{ n(data.summary.slow) }}</strong><small>{{ pct(data.summary.slow_rate) }} · {{ t('of timed calls','占有耗时记录的调用') }}</small></article><article class="panel"><span>{{ t('P95 duration (ms)','P95 耗时（毫秒）') }}</span><strong>{{ n(data.summary.p95_ms) }}</strong></article></div>
        <section class="panel"><h2>{{ t('Coverage and latency','数据覆盖与耗时') }}</h2><p>{{ date(data.start) }} → {{ date(data.end) }}</p><p>{{ t('Timing coverage','耗时覆盖率') }}：{{ pct(data.summary.timing_coverage) }} · P50：{{ n(data.summary.p50_ms) }} ms · P99：{{ n(data.summary.p99_ms) }} ms · {{ t('Cancelled','已取消') }}：{{ n(data.summary.cancelled) }}</p><p v-if="data.summary.total<20" class="notice">{{ t('Fewer than 20 calls. These counts describe the available data, not a reliable trend.','当前不足 20 条调用，统计仅描述现有数据，暂不据此判断趋势。') }}</p><p class="muted">{{ t('Only retained reported calls are included. Group labels come from each call; failures and slow calls can overlap. Percentiles describe the selected period, not a service guarantee.','仅分析保留期内已上报调用，分组信息来自每条调用。失败与慢调用可能重叠，耗时分位数仅描述所选区间，不代表服务承诺。') }}</p></section>
        <section class="panel"><h2>{{ t('Where problems are concentrated','问题集中在哪里') }}</h2><p class="muted">{{ t('Up to 50 groups, ordered by failures or slow-call count. For model grouping, select Model calls.','最多展示 50 组，按失败或慢调用数排序。查看模型差异时请选择“模型调用”。') }}</p><div class="insight-table"><table><thead><tr><th>{{ t('Group','分组') }}</th><th>{{ t('Calls','调用数') }}</th><th>{{ t('Failed','失败数') }}</th><th>{{ t('Failure rate','失败比例') }}</th><th>{{ t('Slow','慢调用数') }}</th><th>P95 (ms)</th></tr></thead><tbody><tr v-for="(row,i) in groups" :key="i"><td>{{ groupLabel(row.label) }}</td><td>{{ n(row.total) }}</td><td>{{ n(row.errors) }}</td><td>{{ pct(row.error_rate) }}</td><td>{{ n(row.slow) }}</td><td>{{ n(row.p95_ms) }}</td></tr><tr v-if="!groups.length"><td colspan="6" class="empty-cell">{{ t('No reported calls.','暂无已上报调用。') }}</td></tr></tbody></table></div><QueryControls :page="groupPage" :size="groupSize" :total="data.groups.length" :loading="busy" @change="groupPage=$event" @resize="groupSize=$event;groupPage=0" /></section>
        <section class="panel"><h2>{{ t('Repeated error messages','重复出现的错误') }}</h2><p class="muted">{{ t('Up to 50 exact reported error messages. These are application-provided descriptions, not inferred root causes.','最多展示 50 种相同的已上报错误说明。这些说明由应用提供，不是系统推断的根因。') }}</p><div class="insight-table"><table><thead><tr><th>{{ t('Error message','错误说明') }}</th><th>{{ t('Occurrences','出现次数') }}</th></tr></thead><tbody><tr v-for="(row,i) in errors" :key="i"><td>{{ row.message||t('No error message reported','未上报错误说明') }}</td><td>{{ n(row.count) }}</td></tr><tr v-if="!errors.length"><td colspan="2" class="empty-cell">{{ t('No reported failures.','暂无已上报失败。') }}</td></tr></tbody></table></div><QueryControls :page="errorPage" :size="errorSize" :total="data.error_groups.length" :loading="busy" @change="errorPage=$event" @resize="errorSize=$event;errorPage=0" /></section>
        <section class="panel"><h2>{{ data.scope==='trace'?t('Application requests','应用请求'):t('Model calls','模型调用') }} · {{ data.view==='errors'?t('Failed calls','失败调用'):t('Slow calls','慢调用') }}</h2><div class="insight-table"><table><thead><tr><th>{{ t('Name','名称') }}</th><th>{{ t('Model','模型') }}</th><th>{{ t('Status','状态') }}</th><th>{{ t('Duration (ms)','耗时（毫秒）') }}</th><th>{{ t('Error','错误说明') }}</th><th>{{ t('Started (UTC)','开始时间（UTC）') }}</th></tr></thead><tbody><tr v-for="row in data.items" :key="row.object_id"><td><RouterLink v-if="row.trace_id" :to="`/app/trace/${encodeURIComponent(row.trace_id)}?project=${project}`">{{ row.name||row.object_id }}</RouterLink><span v-else>{{ row.name||row.object_id }}</span></td><td>{{ row.model||'—' }}</td><td>{{ state(row.status) }}</td><td>{{ n(row.duration_ms) }}</td><td>{{ row.error||'—' }}</td><td>{{ date(row.started_at) }}</td></tr><tr v-if="!data.items.length"><td colspan="6" class="empty-cell">{{ t('No matching calls.','暂无符合条件的调用。') }}</td></tr></tbody></table></div><QueryControls :page="page" :size="size" :total="data.total" :loading="busy" @change="page=$event;load()" @resize="size=$event;page=0;load()" /></section>
      </template>
    </template>
  </section>
</template>
