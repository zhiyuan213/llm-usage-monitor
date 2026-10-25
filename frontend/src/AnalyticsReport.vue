<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { api, t, n, money, date, language, errorText } from './lib';
import QueryControls from './QueryControls.vue';
const props=defineProps<{ project:string; refreshToken:any }>();
const currentYear=new Date().getUTCFullYear();
const years=Array.from({length:4},(_,i)=>currentYear-i);
const draftDimension=ref('customerId'), period=ref('year'), year=ref(currentYear), month=ref(new Date().getUTCMonth()+1);
const applied=ref({dimension:'customerId',year:currentYear,month:0});
const page=ref(0), size=ref(5), total=ref(0), rows=ref<any[]>([]), chart=ref<any[]>([]), busy=ref(false), error=ref('');
let version=0;
const params=computed(()=>new URLSearchParams({project:props.project,dimension:applied.value.dimension,year:String(applied.value.year),...(applied.value.month?{month:String(applied.value.month)}:{})}).toString());
const exportUrl=computed(()=>'/api/reports/analytics/export?'+params.value+'&lang='+language.value);
const periodLabel=computed(()=>applied.value.month?`${applied.value.year}-${String(applied.value.month).padStart(2,'0')}`:String(applied.value.year));
async function load(){
  if(!props.project)return;
  const current=++version;busy.value=true;error.value='';
  try{
    const result=await api(`/reports/analytics?${params.value}&page=${page.value}&size=${size.value}`);
    if(current!==version)return;
    rows.value=result.items;chart.value=result.chart||[];total.value=result.total;
    const last=Math.max(0,Math.ceil(total.value/size.value)-1);
    if(page.value>last){page.value=last;await load();}
  }catch(e){if(current===version){error.value=errorText(e);rows.value=[];chart.value=[];total.value=0;}}
  finally{if(current===version)busy.value=false;}
}
function query(){applied.value={dimension:draftDimension.value,year:year.value,month:period.value==='month'?month.value:0};page.value=0;load();}
watch(()=>props.project,()=>{page.value=0;load();},{immediate:true});
watch(()=>props.refreshToken,()=>load());
const chartWidth=computed(()=>Math.max(600,80+chart.value.length*84));
const maximum=computed(()=>{
  const max=Math.max(1,...chart.value.filter(r=>r.tokens!=null).map(r=>Number(r.tokens)));
  const step=10**Math.floor(Math.log10(max));return Math.max(4,Math.ceil(max/step)*step);
});
const ticks=computed(()=>Array.from({length:5},(_,i)=>maximum.value*i/4));
const barWidth=computed(()=>(chartWidth.value-80)/Math.max(1,chart.value.length));
function label(row:any){return row.dimension||t('Not provided','未提供');}
function tokenLabel(row:any){return row.tokens==null?t('Unknown','未知'):n(row.tokens);}
</script>
<template>
  <div class="analytics-report">
    <div class="analytics-report-heading"><p class="muted">{{periodLabel}} · UTC · {{t('Only data within your plan retention is included.','仅统计套餐保留期内的数据。')}}</p><div class="query-export"><a class="btn" :aria-disabled="busy||!!error" :href="busy||error?undefined:exportUrl+'&format=xlsx'">{{t('Export Excel','导出 Excel')}}</a><a class="btn" :aria-disabled="busy||!!error" :href="busy||error?undefined:exportUrl+'&format=pdf'">{{t('Export PDF','导出 PDF')}}</a></div></div>
    <p v-if="error" class="notice error" role="alert">{{error}}</p>
    <div class="analytics-report-grid">
      <section class="panel analytics-list-panel" :aria-busy="busy">
        <form class="analytics-filters" @submit.prevent="query">
          <label>{{t('Dimension','统计维度')}}<select v-model="draftDimension" :disabled="busy"><option value="customerId">{{t('Customers','客户')}}</option><option value="userId">{{t('Users','用户')}}</option><option value="feature">{{t('Features','功能')}}</option></select></label>
          <label>{{t('Period','时间维度')}}<select v-model="period" :disabled="busy"><option value="year">{{t('Year','年')}}</option><option value="month">{{t('Month','月')}}</option></select></label>
          <label>{{t('Year','年份')}}<select v-model="year" :disabled="busy"><option v-for="value in years" :key="value" :value="value">{{value}}</option></select></label>
          <label v-if="period==='month'">{{t('Month','月份')}}<select v-model="month" :disabled="busy"><option v-for="value in 12" :key="value" :value="value">{{value}}{{t('','月')}}</option></select></label>
          <button class="btn primary" :disabled="busy">{{busy?t('Loading…','查询中…'):t('Search','查询')}}</button>
        </form>
        <div class="table-wrap"><table><thead><tr><th>{{t('Identifier','标识')}}</th><th>{{t('Objects','对象数')}}</th><th>{{t('Known tokens','已知 Token')}}</th><th>{{t('Estimated cost','估算成本')}}</th><th>{{t('Unknown costs','未知成本调用')}}</th><th>{{t('Last activity (UTC)','最近活动时间（UTC）')}}</th></tr></thead><tbody>
          <tr v-for="row in rows" :key="row.dimension??'missing'"><td>{{label(row)}}</td><td>{{n(row.objects)}}</td><td>{{tokenLabel(row)}}</td><td>{{money(row.cost)}}</td><td>{{n(row.unknown_costs)}}</td><td>{{date(row.last_activity_at)}}</td></tr>
          <tr v-if="!rows.length"><td colspan="6" class="empty-cell">{{busy?t('Loading…','加载中…'):t('No data in this period.','所选时间范围内暂无数据。')}}</td></tr>
        </tbody></table></div>
        <QueryControls :page="page" :size="size" :total="total" :loading="busy" @change="page=$event;load()" @resize="size=$event;page=0;load()" />
      </section>
      <section class="panel analytics-chart-panel" :aria-busy="busy">
        <h2>{{t('Token usage by identifier','各标识 Token 用量')}}</h2><p class="muted">{{t('Top 20 identifiers by known Token usage across the selected period; independent of list pagination.','展示所选时间范围内已知 Token 用量最高的前20个标识，不随列表翻页变化。')}}</p>
        <div v-if="chart.length" class="analytics-chart-scroll">
          <svg class="analytics-token-chart" :width="chartWidth" height="400" :viewBox="`0 0 ${chartWidth} 400`" role="img" :aria-label="t('Token usage bar chart','Token 用量柱状图')">
            <text x="12" y="18" class="chart-axis-title">Token</text>
            <g v-for="tick in ticks" :key="tick"><line x1="65" :x2="chartWidth-15" :y1="320-tick/maximum*275" :y2="320-tick/maximum*275" class="chart-grid-line" /><text x="57" :y="324-tick/maximum*275" text-anchor="end">{{new Intl.NumberFormat(language,{notation:'compact',maximumFractionDigits:1}).format(tick)}}</text></g>
            <g v-for="(row,i) in chart" :key="row.dimension??'missing'"><title>{{label(row)}}: {{tokenLabel(row)}} Token</title><rect v-if="row.tokens!=null" :x="76+i*barWidth" :y="320-Number(row.tokens)/maximum*275" :width="Math.max(4,barWidth-24)" :height="Number(row.tokens)/maximum*275" rx="3" class="analytics-bar" tabindex="0" :aria-label="`${label(row)}: ${tokenLabel(row)} Token`" /><text v-else :x="65+(i+.5)*barWidth" y="305" text-anchor="middle">{{t('Unknown','未知')}}</text><text :x="65+(i+.5)*barWidth" y="344" text-anchor="middle">{{label(row).length>12?label(row).slice(0,4)+'…'+label(row).slice(-5):label(row)}}</text></g>
            <text :x="chartWidth/2" y="384" text-anchor="middle" class="chart-axis-title">{{t('Identifier','标识')}}</text>
          </svg>
        </div>
        <div v-else class="empty-cell">{{busy?t('Loading…','加载中…'):t('No data in this period.','所选时间范围内暂无数据。')}}</div>
        <p class="muted">{{t('Missing Token usage is unknown, not zero. Hover over a bar to see the full identifier and usage.','未上报 Token 的数据为未知，不按零处理。悬停柱形可查看完整标识和用量。')}}</p>
      </section>
    </div>
  </div>
</template>
