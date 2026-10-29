<script setup lang="ts">
import {usePageSize} from "./page-size";
import { computed, ref, watch } from 'vue';
import { api, t, n, money, date, language, errorText } from './lib';
import DateRangeFilter from './DateRangeFilter.vue';
import {monthDates,customWindow as makeWindow,type ReportWindow} from './date-range';
import QueryControls from './QueryControls.vue';
import AnalyticsBarChart from './AnalyticsBarChart.vue';
const props=defineProps<{ project:string; refreshToken:any }>();
const draftDimension=ref('customerId');
const dates=monthDates();
const appliedWindow=ref(makeWindow(dates.from,dates.to)!);
const appliedDimension=ref('customerId');
const page=ref(0), size=usePageSize(), total=ref(0), rows=ref<any[]>([]), tokenChart=ref<any[]>([]), costChart=ref<any[]>([]), busy=ref(false), error=ref('');
const dimensionName=computed(()=>appliedDimension.value==='customerId'?t('Customer ID','客户 ID'):appliedDimension.value==='userId'?t('User ID','用户 ID'):t('Feature','功能'));
function label(row:any){return row.dimension||t('Not provided','未提供');}
let version=0;
const params=computed(()=>new URLSearchParams({project:props.project,dimension:appliedDimension.value,...appliedWindow.value}).toString());
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
function query(value:ReportWindow){appliedWindow.value=value;appliedDimension.value=draftDimension.value;page.value=0;load();}
watch(()=>props.project,()=>{page.value=0;load();},{immediate:true});
watch(()=>props.refreshToken,()=>load());
</script>
<template>
  <div class="analytics-report">
    <p v-if="error" class="notice error" role="alert">{{error}}</p>
    <DateRangeFilter class="analytics-query-toolbar" initial="custom" :busy="busy" @change="query">
      <label>{{t('Dimension','统计维度')}}<select v-model="draftDimension" :disabled="busy"><option value="customerId">{{t('Customers','客户')}}</option><option value="userId">{{t('Users','用户')}}</option><option value="feature">{{t('Features','功能')}}</option></select></label>
      <template #actions><div class="query-export"><a class="btn" :aria-disabled="busy||!!error" :href="busy||error?undefined:exportUrl+'&format=xlsx'">{{t('Export Excel','导出 Excel')}}</a><a class="btn" :aria-disabled="busy||!!error" :href="busy||error?undefined:exportUrl+'&format=pdf'">{{t('Export PDF','导出 PDF')}}</a></div></template>
    </DateRangeFilter>
    <p class="analytics-grouping-note">{{t('Grouped by','当前按')}} <strong>{{dimensionName}}</strong>（<code>data.{{appliedDimension}}</code>）{{t('; repeated values are combined. Missing IDs appear under Not provided. Row count is the number of groups, not requests.','汇总；同一标识合并为一行，未上报归入“未提供”。列表条数是分组数，不是请求数。')}}</p>
    <div class="analytics-report-grid">
      <section class="panel analytics-list-panel" :aria-busy="busy">
        <div class="panel-heading"><h2>{{dimensionName}} {{t('statistics','统计')}}</h2><span class="badge">{{total}} {{t('groups','组')}}</span></div>
        <div class="table-wrap"><table><thead><tr><th>{{dimensionName}}</th><th>{{t('Objects','对象数')}}</th><th>{{t('Known tokens','已知 Token')}}</th><th>{{t('Estimated cost','估算成本')}}</th><th>{{t('Unknown costs','未知成本调用')}}</th><th>{{t('Last activity (UTC)','最近活动时间（UTC）')}}</th></tr></thead><tbody>
          <tr v-for="row in rows" :key="row.dimension??'missing'"><td>{{label(row)}}</td><td>{{n(row.objects)}}</td><td>{{row.tokens==null?t('Unknown','未知'):n(row.tokens)}}</td><td>{{money(row.cost)}}</td><td>{{n(row.unknown_costs)}}</td><td>{{date(row.last_activity_at)}}</td></tr>
          <tr v-if="!rows.length"><td colspan="6" class="empty-cell">{{busy?t('Loading…','加载中…'):t('No data in this period.','所选时间范围内暂无数据。')}}</td></tr>
        </tbody></table></div>
        <QueryControls :page="page" :size="size" :total="total" :loading="busy" @change="page=$event;load()" @resize="size=$event;page=0;load()" />
      </section>
      <div class="analytics-chart-stack"><AnalyticsBarChart :rows="tokenChart" metric="tokens" :dimension-label="dimensionName" :busy="busy" /><AnalyticsBarChart :rows="costChart" metric="cost" :dimension-label="dimensionName" :busy="busy" /></div>
    </div>
  </div>
</template>
