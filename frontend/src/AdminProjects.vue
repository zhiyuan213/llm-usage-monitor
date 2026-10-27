<script setup lang="ts">
import {usePageSize} from "./page-size";
import {computed,ref,watch} from 'vue';
import {api,t,n,money,language,errorText,session} from './lib';
import QueryControls from './QueryControls.vue';
const props=defineProps<{refreshToken:any}>();
const currentYear=new Date().getUTCFullYear(), years=Array.from({length:4},(_,i)=>currentYear-i);
const account=ref(''),accounts=ref<any[]>([]),period=ref('year'),year=ref(currentYear),month=ref(new Date().getUTCMonth()+1);
const applied=ref({account:'',year:currentYear,month:0});
const page=ref(0),size=usePageSize(),total=ref(0),rows=ref<any[]>([]),tokenChart=ref<any[]>([]),costChart=ref<any[]>([]),busy=ref(false),error=ref('');
const metric=ref<'tokens'|'cost'>('tokens');
const chart=computed(()=>metric.value==='tokens'?tokenChart.value:costChart.value);
const sort=ref('email'),direction=ref('asc');
const columns=[['email','Account','账号'],['name','Project','项目'],['environment','Environment','环境'],['calls','Calls','调用'],['objects','Objects','对象'],['tokens','Total tokens','Token 总数'],['cost','Estimated cost','成本总数'],['unknown_costs','Unknown costs','未知成本调用']];
function order(key:string){if(busy.value)return;direction.value=sort.value===key&&direction.value==='asc'?'desc':'asc';sort.value=key;page.value=0;load();}
let version=0;
async function load(){
 const current=++version;busy.value=true;error.value='';
 try{
  if(!session.value?.isAdmin)throw new Error(t('Administrator access is required.','仅管理员可以访问。'));
  const params=new URLSearchParams({year:String(applied.value.year),page:String(page.value),size:String(size.value),sort:sort.value,direction:direction.value});
  if(applied.value.account)params.set('account',applied.value.account);
  if(applied.value.month)params.set('month',String(applied.value.month));
  const result=await api('/admin/projects?'+params);
  if(current!==version)return;
  rows.value=result.items;total.value=result.total;accounts.value=result.accounts;tokenChart.value=result.chart;costChart.value=result.chart_costs;
  const last=Math.max(0,Math.ceil(total.value/size.value)-1);if(page.value>last){page.value=last;await load();}
 }catch(e){if(current===version){error.value=errorText(e);rows.value=[];tokenChart.value=[];costChart.value=[];total.value=0;}}
 finally{if(current===version)busy.value=false;}
}
function query(){applied.value={account:account.value,year:year.value,month:period.value==='month'?month.value:0};page.value=0;load();}
watch(()=>props.refreshToken,load,{immediate:true});
const chartWidth=computed(()=>Math.max(600,80+chart.value.length*84));
const maximum=computed(()=>{
  const max=Math.max(0,...chart.value.filter(r=>value(r)!=null).map(r=>Number(value(r))));
  if(max===0)return metric.value==='tokens'?4:1;
  const step=10**Math.floor(Math.log10(max));const result=Math.ceil(max/step)*step;return metric.value==='tokens'?Math.max(4,result):result;
});
const ticks=computed(()=>Array.from({length:5},(_,i)=>maximum.value*i/4));
const barWidth=computed(()=>(chartWidth.value-80)/Math.max(1,chart.value.length));
const columnWidth=computed(()=>Math.min(28,Math.max(4,barWidth.value-24)));
function label(row:any){return row.dimension||t('Not provided','未提供');}
function value(row:any){return row[metric.value];}
function costNumber(value:number){return value!==0 && Math.abs(value)<0.0001?value.toExponential(1):new Intl.NumberFormat(language.value,{maximumFractionDigits:8}).format(value);}
function valueLabel(row:any){return value(row)==null?t('Unknown','未知'):metric.value==='tokens'?n(value(row)):costNumber(Number(value(row)));}
function tooltip(row:any){return `${label(row)}: ${valueLabel(row)} ${metric.value==='tokens'?'Token':'USD'}`;}
function tickLabel(value:number){return metric.value==='cost'?costNumber(value):new Intl.NumberFormat(language.value,{notation:'compact',maximumFractionDigits:1}).format(value);}
</script>
<template><div class="analytics-report admin-projects-report">
<p class="muted">{{t('UTC calendar periods; only retained project data is included. Calls count traces; costs are estimates, not subscription revenue.','按 UTC 自然年或自然月统计，仅包含项目保留期内的数据。调用统计 Trace；成本为模型估算费用，不是订阅收入。')}}</p>
<p v-if="error" class="notice error" role="alert">{{error}}</p>
<div class="analytics-report-grid">
<section class="panel analytics-list-panel" :aria-busy="busy">
<form class="analytics-filters" @submit.prevent="query">
<label>{{t('Account','账号')}}<select v-model="account" :disabled="busy"><option value="">{{t('All accounts','全部账号')}}</option><option v-for="a in accounts" :key="a.id" :value="a.id">{{a.email}}</option></select></label>
<label>{{t('Period','时间维度')}}<select v-model="period" :disabled="busy"><option value="year">{{t('Year','年')}}</option><option value="month">{{t('Month','月')}}</option></select></label>
<label>{{t('Year','年份')}}<select v-model="year" :disabled="busy"><option v-for="y in years" :key="y" :value="y">{{y}}</option></select></label>
<label v-if="period==='month'">{{t('Month','月份')}}<select v-model="month" :disabled="busy"><option v-for="m in 12" :key="m" :value="m">{{m}}</option></select></label>
<button class="btn primary" :disabled="busy">{{busy?t('Loading…','查询中…'):t('Search','查询')}}</button>
</form>
<div class="table-wrap"><table><thead><tr><th v-for="c in columns" :key="c[0]" :aria-sort="sort===c[0]?(direction==='asc'?'ascending':'descending'):'none'"><button type="button" class="admin-sort" :disabled="busy" @click="order(c[0])">{{t(c[1],c[2])}} <span aria-hidden="true">{{sort===c[0]?(direction==='asc'?'↑':'↓'):'↕'}}</span></button></th></tr></thead><tbody>
<tr v-for="r in rows" :key="r.id"><td>{{r.email}}</td><td>{{r.name}}</td><td>{{r.environment}}</td><td>{{n(r.calls)}}</td><td>{{n(r.objects)}}</td><td>{{r.tokens==null?t('Unknown','未知'):n(r.tokens)}}</td><td>{{money(r.cost)}}</td><td>{{n(r.unknown_costs)}}</td></tr>
<tr v-if="!rows.length"><td colspan="8" class="empty-cell">{{busy?t('Loading…','加载中…'):t('No projects.','暂无项目。')}}</td></tr>
</tbody></table></div>
<QueryControls :page="page" :size="size" :total="total" :loading="busy" @change="page=$event;load()" @resize="size=$event;page=0;load()" />
</section>
      <section class="panel analytics-chart-panel" :aria-busy="busy">
        <div class="analytics-chart-heading"><h2>{{metric==='tokens'?t('Token usage by project','各项目 Token 用量'):t('Estimated cost by project','各项目估算成本')}}</h2><label><span class="muted">{{t('Chart metric','图表指标')}}</span><select v-model="metric" :disabled="busy" :aria-label="t('Chart metric','图表指标')"><option value="tokens">{{t('Token usage','Token用量')}}</option><option value="cost">{{t('Estimated cost (USD)','估算成本（USD）')}}</option></select></label></div><p class="muted">{{metric==='tokens'?t('Top 20 projects by known Token usage; independent of list pagination.','展示所选时间范围内已知 Token 用量最高的前20个项目，不随列表翻页变化。'):t('Top 20 projects by known estimated cost; missing costs are not zero.','展示所选时间范围内已知估算成本最高的前20个项目；缺失成本不按零处理。')}}</p>
        <div v-if="chart.length" class="analytics-chart-scroll">
          <svg class="analytics-token-chart" :width="chartWidth" height="400" :viewBox="`0 0 ${chartWidth} 400`" role="img" :aria-label="metric==='tokens'?t('Token usage bar chart','Token 用量柱状图'):t('Estimated cost bar chart','估算成本柱状图')">
            <defs><linearGradient id="token-usage-bar-fill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#7b91f5" /><stop offset="100%" stop-color="#4967db" /></linearGradient></defs>
            <text x="12" y="18" class="chart-axis-title">{{metric==='tokens'?'Token':'USD'}}</text>
            <g v-for="tick in ticks" :key="tick"><line x1="65" :x2="chartWidth-15" :y1="320-tick/maximum*275" :y2="320-tick/maximum*275" class="chart-grid-line" /><text x="57" :y="324-tick/maximum*275" text-anchor="end">{{tickLabel(tick)}}</text></g>
            <g v-for="(row,i) in chart" :key="row.dimension??'missing'"><title>{{tooltip(row)}}</title><rect v-if="value(row)!=null" :x="65+(i+.5)*barWidth-columnWidth/2" :y="320-Number(value(row))/maximum*275" :width="columnWidth" :height="Number(value(row))/maximum*275" rx="4" class="analytics-bar" tabindex="0" :aria-label="tooltip(row)" /><text v-if="value(row)!=null" :x="65+(i+.5)*barWidth" :y="Math.max(33,310-Number(value(row))/maximum*275)" text-anchor="middle" class="analytics-bar-value">{{valueLabel(row)}}</text><text v-else :x="65+(i+.5)*barWidth" y="305" text-anchor="middle">{{t('Unknown','未知')}}</text><text :x="65+(i+.5)*barWidth" y="344" text-anchor="middle">{{label(row).length>12?label(row).slice(0,4)+'…'+label(row).slice(-5):label(row)}}</text></g>
            <text :x="chartWidth/2" y="384" text-anchor="middle" class="chart-axis-title">{{t('Identifier','项目')}}</text>
          </svg>
        </div>
        <div v-else class="empty-cell">{{busy?t('Loading…','加载中…'):t('No data in this period.','所选时间范围内暂无数据。')}}</div>
        <p class="muted">{{t('Unknown values are not zero. Hover over a bar for the full identifier and value. Estimated model costs exclude MeterTrail subscription charges.','未知数值不按零处理；悬停柱形可查看完整项目和数值。估算成本不包含 MeterTrail 订阅费。')}}</p>
      </section>
</div></div></template>
