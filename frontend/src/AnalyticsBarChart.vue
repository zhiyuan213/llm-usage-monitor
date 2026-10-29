<script setup lang="ts">
import {computed} from 'vue';
import {t,n,language} from './lib';
import {useChartWidth} from './chart-width';
const props=defineProps<{rows:any[];metric:'tokens'|'cost';dimensionLabel:string;busy:boolean}>();
const {chartContainer,containerWidth}=useChartWidth();
const chart=computed(()=>props.rows);
const metric=computed(()=>props.metric);
const chartWidth=computed(()=>Math.max(600,containerWidth.value,80+chart.value.length*84));
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
<template>      <section class="panel analytics-chart-panel" :aria-busy="busy">
        <div class="analytics-chart-heading"><h2>{{metric==='tokens'?t('Token usage','Token 用量'):t('Estimated cost (USD)','估算费用（USD）')}}</h2><span class="badge">{{dimensionLabel}}</span></div><p class="muted">{{metric==='tokens'?t('Top 20 identifiers by known Token usage; independent of list pagination.','展示所选时间范围内已知 Token 用量最高的前20个标识，不随列表翻页变化。'):t('Top 20 identifiers by known estimated cost; missing costs are not zero.','展示所选时间范围内已知估算成本最高的前20个标识；缺失成本不按零处理。')}}</p>
        <div v-if="chart.length" ref="chartContainer" class="analytics-chart-scroll">
          <svg class="analytics-token-chart analytics-compact-chart" :width="chartWidth" height="260" :viewBox="`0 0 ${chartWidth} 400`" role="img" :aria-label="metric==='tokens'?t('Token usage bar chart','Token 用量柱状图'):t('Estimated cost bar chart','估算成本柱状图')">
            <defs><linearGradient :id="'analytics-fill-'+metric" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#7b91f5" /><stop offset="100%" stop-color="#4967db" /></linearGradient></defs>
            <text x="12" y="18" class="chart-axis-title">{{metric==='tokens'?'Token':'USD'}}</text>
            <g v-for="tick in ticks" :key="tick"><line x1="65" :x2="chartWidth-15" :y1="320-tick/maximum*275" :y2="320-tick/maximum*275" class="chart-grid-line" /><text x="57" :y="324-tick/maximum*275" text-anchor="end">{{tickLabel(tick)}}</text></g>
            <g v-for="(row,i) in chart" :key="row.dimension??'missing'"><title>{{tooltip(row)}}</title><rect v-if="value(row)!=null" :x="65+(i+.5)*barWidth-columnWidth/2" :y="320-Number(value(row))/maximum*275" :width="columnWidth" :height="Number(value(row))/maximum*275" rx="4" class="analytics-bar" :style="{fill:'url(#analytics-fill-'+metric+')'}" tabindex="0" :aria-label="tooltip(row)" /><text v-if="value(row)!=null" :x="65+(i+.5)*barWidth" :y="Math.max(33,310-Number(value(row))/maximum*275)" text-anchor="middle" class="analytics-bar-value">{{valueLabel(row)}}</text><text v-else :x="65+(i+.5)*barWidth" y="305" text-anchor="middle">{{t('Unknown','未知')}}</text><text :x="65+(i+.5)*barWidth" y="344" text-anchor="middle">{{label(row).length>12?label(row).slice(0,4)+'…'+label(row).slice(-5):label(row)}}</text></g>
            <text :x="chartWidth/2" y="384" text-anchor="middle" class="chart-axis-title">{{t('Identifier','标识')}}</text>
          </svg>
        </div>
        <div v-else class="empty-cell">{{busy?t('Loading…','加载中…'):t('No data in this period.','所选时间范围内暂无数据。')}}</div>
        <p class="muted">{{t('Unknown values are not zero. Hover over a bar for the full identifier and value. Estimated model costs exclude MeterTrail subscription charges.','未知数值不按零处理；悬停柱形可查看完整标识和数值。估算成本不包含 MeterTrail 订阅费。')}}</p>
      </section></template>
