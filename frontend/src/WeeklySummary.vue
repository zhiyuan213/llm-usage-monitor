<script setup lang="ts">
import { t, money, n, date } from './lib';
defineProps<{report:any;project:string}>();
</script>
<template>
  <section class="panel weekly-summary">
    <div class="insight-toolbar"><h2>{{ report.example?t('Weekly report example','周报示例'):t('Weekly summary','周报摘要') }}</h2><span v-if="report.example" class="notice">{{ t('Synthetic data · not your project statistics','模拟数据 · 非你的项目统计') }}</span></div>
    <p>{{ t('Period (UTC, end exclusive)','统计区间（UTC，不含结束时刻）') }}：{{ date(report.after.start) }} → {{ date(report.after.end) }}</p>
    <p v-if="!report.after.requests&&!report.after.generations" class="notice">{{ t('No requests or model calls were reported during this week.','本周暂无已上报请求或模型调用。') }}</p>
    <div class="insight-metrics">
      <article><span>{{ t('Known estimated model cost','已知模型估算费用') }}</span><strong>{{ money(report.after.known_cost) }}</strong></article>
      <article><span>{{ t('Requests','请求数') }}</span><strong>{{ n(report.after.requests) }}</strong></article>
      <article><span>{{ t('Failed requests','失败请求') }}</span><strong>{{ n(report.after.errors) }}</strong></article>
      <article><span>{{ t('P95 duration (ms)','P95 耗时（毫秒）') }}</span><strong>{{ n(report.after.p95_ms) }}</strong></article>
    </div>
    <p>{{ t('Model calls','模型调用数') }}：{{ n(report.after.generations) }} · {{ t('Priced calls','已定价调用') }}：{{ n(report.after.priced_generations) }}</p>
    <p v-if="report.comparison?.cost_change_pct!=null">{{ t('Known cost change from previous week','较上周已知费用变化') }}：{{ n(report.comparison.cost_change_pct) }}%</p>
    <p v-if="report.after.priced_generations<report.after.generations" class="notice">{{ t('Some model calls have unknown cost. The known subtotal is not the total cost.','部分模型调用费用未知，已知费用不代表完整总费用。') }}</p>
    <p class="muted">{{ t('Model costs are estimates, excluding MeterTrail subscription charges. Open the analysis pages for details.','模型费用为估算值，不含 MeterTrail 订阅费。详细分析请前往对应页面。') }}</p>
    <div class="insight-toolbar"><RouterLink :to="`/app/cost-insights?project=${project}`">{{ t('Cost diagnosis →','查看费用诊断 →') }}</RouterLink><RouterLink :to="`/app/reliability?project=${project}`">{{ t('Failures and slow calls →','查看失败与慢调用 →') }}</RouterLink></div>
  </section>
</template>
