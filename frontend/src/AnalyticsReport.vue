<script setup lang="ts">
import {usePageSize} from "./page-size";
import { computed, ref, watch } from 'vue';
import { api, t, n, money, date, language, errorText } from './lib';
import DateRangeFilter from './DateRangeFilter.vue';
import QueryControls from './QueryControls.vue';
const props=defineProps<{ project:string; refreshToken:any }>();
const currentYear=new Date().getUTCFullYear();
const years=Array.from({length:4},(_,i)=>currentYear-i);
const draftDimension=ref('customerId'), period=ref('year'), year=ref(currentYear), month=ref(new Date().getUTCMonth()+1);
const customWindow=ref<{from:string;to:string}|null>(null);
const applied=ref({dimension:'customerId',year:currentYear,month:0});
const page=ref(0), size=usePageSize(), total=ref(0), rows=ref<any[]>([]), tokenChart=ref<any[]>([]), costChart=ref<any[]>([]), busy=ref(false), error=ref('');
const metric=ref<'tokens'|'cost'>('tokens');
const chart=computed(()=>metric.value==='tokens'?tokenChart.value:costChart.value);
let version=0;
const params=computed(()=>new URLSearchParams({project:props.project,dimension:applied.value.dimension,...(customWindow.value?customWindow.value:{year:String(applied.value.year),...(applied.value.month?{month:String(applied.value.month)}:{})})}).toString());
const exportUrl=computed(()=>'/api/reports/analytics/export?'+params.value+'&lang='+language.value);
async function load(){
  if(!props.project)return;
  const current=++version;busy.value=true;error.value='';
  try{
    const result=await api(`/reports/analytics?${params.value}&page=${page.value}&size=${size.value}`);
    if(current!==version)return;
    rows.value=result.items;tokenChart.value=result.chart||[];costChart.value=result.chart_costs||[];total.value=result.total;
    const last=Math.max(0,Math.ceil(total.value/size.value)-1);
    if(page.value>last){page.value=last;await load();}
  }catch(e){if(current===version){error.value=errorText(e);rows.value=[];tokenChart.value=[];costChart.value=[];total.value=0;}}
  finally{if(current===version)busy.value=false;}
}
function query(){customWindow.value=null;applied.value={dimension:draftDimension.value,year:year.value,month:period.value==='month'?month.value:0};page.value=0;load();}
watch(()=>props.project,()=>{page.value=0;load();},{immediate:true});
watch(()=>props.refreshToken,()=>load());
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
<template>
  <div class="analytics-report">
    <div class="analytics-report-heading"><div class="query-export"><a class="btn" :aria-disabled="busy||!!error" :href="busy||error?undefined:exportUrl+'&format=xlsx'">{{t('Export Excel','导出 Excel')}}</a><a class="btn" :aria-disabled="busy||!!error" :href="busy||error?undefined:exportUrl+'&format=pdf'">{{t('Export PDF','导出 PDF')}}</a></div></div>
    <p v-if="error" class="notice error" role="alert">{{error}}</p>
    <div class="analytics-top-controls">
        <DateRangeFilter initial="" :busy="busy" @change="customWindow=$event;applied.dimension=draftDimension;page=0;load()" /><form class="analytics-filters" @submit.prevent="query">
          <label>{{t('Dimension','统计维度')}}<select v-model="draftDimension" :disabled="busy"><option value="customerId">{{t('Customers','客户')}}</option><option value="userId">{{t('Users','用户')}}</option><option value="feature">{{t('Features','功能')}}</option></select></label>
          <label>{{t('Period','时间维度')}}<select v-model="period" :disabled="busy"><option value="year">{{t('Year','年')}}</option><option value="month">{{t('Month','月')}}</option></select></label>
          <label>{{t('Year','年份')}}<select v-model="year" :disabled="busy"><option v-for="value in years" :key="value" :value="value">{{value}}</option></select></label>
          <label v-if="period==='month'">{{t('Month','月份')}}<select v-model="month" :disabled="busy"><option v-for="value in 12" :key="value" :value="value">{{value}}{{t('','月')}}</option></select></label>
          <button class="btn primary" :disabled="busy">{{busy?t('Loading…','查询中…'):t('Search','查询')}}</button>
        </form>
    </div>
    <div class="analytics-report-grid">
      <section class="panel analytics-list-panel" :aria-busy="busy">
        <div class="table-wrap"><table><thead><tr><th>{{t('Identifier','标识')}}</th><th>{{t('Objects','对象数')}}</th><th>{{t('Known tokens','已知 Token')}}</th><th>{{t('Estimated cost','估算成本')}}</th><th>{{t('Unknown costs','未知成本调用')}}</th><th>{{t('Last activity (UTC)','最近活动时间（UTC）')}}</th></tr></thead><tbody>
          <tr v-for="row in rows" :key="row.dimension??'missing'"><td>{{label(row)}}</td><td>{{n(row.objects)}}</td><td>{{row.tokens==null?t('Unknown','未知'):n(row.tokens)}}</td><td>{{money(row.cost)}}</td><td>{{n(row.unknown_costs)}}</td><td>{{date(row.last_activity_at)}}</td></tr>
          <tr v-if="!rows.length"><td colspan="6" class="empty-cell">{{busy?t('Loading…','加载中…'):t('No data in this period.','所选时间范围内暂无数据。')}}</td></tr>
        </tbody></table></div>
        <QueryControls :page="page" :size="size" :total="total" :loading="busy" @change="page=$event;load()" @resize="size=$event;page=0;load()" />
      </section>
      <section class="panel analytics-chart-panel" :aria-busy="busy">
        <div class="analytics-chart-heading"><h2>{{metric==='tokens'?t('Token usage by identifier','各标识 Token 用量'):t('Estimated cost by identifier','各标识估算成本')}}</h2><label><span class="muted">{{t('Chart metric','图表指标')}}</span><select v-model="metric" :disabled="busy" :aria-label="t('Chart metric','图表指标')"><option value="tokens">{{t('Token usage','Token用量')}}</option><option value="cost">{{t('Estimated cost (USD)','估算成本（USD）')}}</option></select></label></div><p class="muted">{{metric==='tokens'?t('Top 20 identifiers by known Token usage; independent of list pagination.','展示所选时间范围内已知 Token 用量最高的前20个标识，不随列表翻页变化。'):t('Top 20 identifiers by known estimated cost; missing costs are not zero.','展示所选时间范围内已知估算成本最高的前20个标识；缺失成本不按零处理。')}}</p>
        <div v-if="chart.length" class="analytics-chart-scroll">
          <svg class="analytics-token-chart" :width="chartWidth" height="400" :viewBox="`0 0 ${chartWidth} 400`" role="img" :aria-label="metric==='tokens'?t('Token usage bar chart','Token 用量柱状图'):t('Estimated cost bar chart','估算成本柱状图')">
            <defs><linearGradient id="token-usage-bar-fill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#7b91f5" /><stop offset="100%" stop-color="#4967db" /></linearGradient></defs>
            <text x="12" y="18" class="chart-axis-title">{{metric==='tokens'?'Token':'USD'}}</text>
            <g v-for="tick in ticks" :key="tick"><line x1="65" :x2="chartWidth-15" :y1="320-tick/maximum*275" :y2="320-tick/maximum*275" class="chart-grid-line" /><text x="57" :y="324-tick/maximum*275" text-anchor="end">{{tickLabel(tick)}}</text></g>
            <g v-for="(row,i) in chart" :key="row.dimension??'missing'"><title>{{tooltip(row)}}</title><rect v-if="value(row)!=null" :x="65+(i+.5)*barWidth-columnWidth/2" :y="320-Number(value(row))/maximum*275" :width="columnWidth" :height="Number(value(row))/maximum*275" rx="4" class="analytics-bar" tabindex="0" :aria-label="tooltip(row)" /><text v-if="value(row)!=null" :x="65+(i+.5)*barWidth" :y="Math.max(33,310-Number(value(row))/maximum*275)" text-anchor="middle" class="analytics-bar-value">{{valueLabel(row)}}</text><text v-else :x="65+(i+.5)*barWidth" y="305" text-anchor="middle">{{t('Unknown','未知')}}</text><text :x="65+(i+.5)*barWidth" y="344" text-anchor="middle">{{label(row).length>12?label(row).slice(0,4)+'…'+label(row).slice(-5):label(row)}}</text></g>
            <text :x="chartWidth/2" y="384" text-anchor="middle" class="chart-axis-title">{{t('Identifier','标识')}}</text>
          </svg>
        </div>
        <div v-else class="empty-cell">{{busy?t('Loading…','加载中…'):t('No data in this period.','所选时间范围内暂无数据。')}}</div>
        <p class="muted">{{t('Unknown values are not zero. Hover over a bar for the full identifier and value. Estimated model costs exclude MeterTrail subscription charges.','未知数值不按零处理；悬停柱形可查看完整标识和数值。估算成本不包含 MeterTrail 订阅费。')}}</p>
      </section>
    </div>
  </div>
</template>
