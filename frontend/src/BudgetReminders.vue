<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { api, errorText, t, date, money, n } from './lib';
import QueryControls from './QueryControls.vue';
const props=defineProps<{project:string;refreshToken?:boolean}>();
const busy=ref(false),error=ref(''),notice=ref(''),data=ref<any>(null),enabled=ref(false),forecast=ref(true),amount=ref('100'),warning=ref(80),page=ref(0),size=ref(5);
const available=computed(()=>data.value?.available===true),custom=computed(()=>data.value?.custom_rules===true);
const pct=(value:any)=>value==null?'—':`${n(Number(value)*100)}%`;
let epoch=0,alive=true;onBeforeUnmount(()=>{alive=false;++epoch;});
async function load() {
  const id=++epoch;busy.value=true;error.value='';
  try {const result=await api(`/projects/${props.project}/insights/budget?page=${page.value}&size=${size.value}`);
    if(!alive||id!==epoch)return;data.value=result;enabled.value=result.settings.enabled;forecast.value=result.settings.forecast_enabled;amount.value=String(result.settings.monthly_budget);warning.value=result.settings.warning_percent;
    if(page.value>0&&page.value*size.value>=result.total){page.value=Math.max(0,Math.ceil(result.total/size.value)-1);await load();}
  }catch(e){if(alive&&id===epoch)error.value=errorText(e);}finally{if(alive&&id===epoch)busy.value=false;}
}
watch(()=>props.project,()=>{page.value=0;data.value=null;notice.value='';void load();},{immediate:true});
watch(()=>props.refreshToken,(loading,old)=>{if(old&&!loading)void load();});
async function save() {
  if(busy.value)return;const id=++epoch;busy.value=true;error.value='';notice.value='';
  try {await api(`/projects/${props.project}/insights/budget`,{monthlyBudget:amount.value,warningPercent:warning.value,enabled:enabled.value,forecastEnabled:forecast.value});
    if(alive&&id===epoch){notice.value=enabled.value?t('Budget saved. Reminders will be sent to your verified account email when a threshold is reached.','预算已保存，达到提醒条件时会发送到已验证的账号邮箱。'):t('Budget reminders disabled. Pending deliveries have been cancelled.','预算提醒已关闭，待发送邮件已取消。');await load();}
  }catch(e){if(alive&&id===epoch)error.value=errorText(e);}finally{if(alive&&id===epoch)busy.value=false;}
}
const kind=(v:string)=>({warning:t('Approaching budget','接近预算'),exceeded:t('Budget reached','已达预算'),forecast:t('Projected over budget','预测超预算')}[v]||v);
const state=(v:string)=>({queued:t('Waiting to send','等待发送'),sent:t('Accepted by mail service','邮件服务已接收'),failed:t('Delivery failed','发送失败'),cancelled:t('Cancelled','已取消')}[v]||v);
</script>
<template>
  <section class="business-insights" :aria-busy="busy">
    <div v-if="error" class="notice error" role="alert">{{ error }}</div><div v-if="notice" class="notice success" role="status">{{ notice }}</div><p v-if="busy" role="status">{{ t('Loading…','正在加载…') }}</p>
    <template v-if="data">
      <section class="panel"><h2>{{ t('Monthly model-cost budget','每月模型费用预算') }}</h2><p>{{ t('Per-project calendar month in UTC. This is an email reminder, not a spending cap: it does not stop data ingestion or charge your payment method. MeterTrail subscription fees are excluded.','按项目、UTC 自然月统计。此功能只发邮件提醒，不会停止数据接入或从支付方式扣款，也不包含 MeterTrail 订阅费。') }}</p>
        <p v-if="!data.configured" class="notice"><strong>{{t('Not configured: amounts below are an unsaved draft; no budget reminder is active.','尚未配置：下方金额只是未保存草稿，预算提醒尚未生效。')}}</strong></p><form class="insight-toolbar" @submit.prevent="save">
          <label>{{ data.configured?t('Monthly budget (USD)','月预算（USD）'):t('Draft budget (USD)','预算草稿（USD）') }}<input v-model="amount" type="number" min="0.01" max="1000000000" step="0.01" required :disabled="busy||!available" /></label>
          <label>{{ t('Warn at (%)','提醒线（%）') }}<input v-model.number="warning" type="number" min="50" max="95" step="1" required :disabled="busy||!custom" /></label>
          <label class="insight-check"><input v-model="forecast" type="checkbox" :disabled="busy||!available" />{{ t('Warn if projected month-end cost exceeds budget','预测月末可能超预算时提醒') }}</label>
          <label class="insight-check"><input v-model="enabled" type="checkbox" :disabled="busy||(!available&&!enabled)" />{{ t('Enable email reminders','开启邮件提醒') }}</label>
          <button class="btn primary" :disabled="busy||(!available&&enabled)">{{ t('Save budget','保存预算') }}</button>
        </form><p>{{ data.settings.enabled?t('Email reminders enabled','邮件提醒已开启'):t('Email reminders disabled','邮件提醒未开启') }}</p>
        <p v-if="!available" class="notice">{{ t('Core or Pro is required to enable reminders. Existing reminders can still be disabled.','开启预算提醒需要 Core 或 Pro，已有提醒仍可关闭。') }} <RouterLink to="/app/billing">{{ t('View plans','查看套餐') }}</RouterLink></p>
        <p v-else-if="!custom" class="muted">{{ t('Core warns at 80% and 100%. Pro can customize the early warning from 50% to 95%.','Core 在达到预算的 80% 和 100% 时提醒；Pro 可将提前提醒线设为 50%～95%。') }}</p>
        <details class="notification-rules"><summary>{{t('Reminder frequency and changes','提醒频率与修改规则')}}</summary><p class="muted">{{ t('Checks run about every 5 minutes. Each warning level is generated at most once per project per UTC month, even after budget edits. Disabling cancels pending deliveries; mail already in progress may still arrive.','约每 5 分钟检查一次。每项目、每 UTC 自然月、每类提醒最多生成一次，修改预算不会重置次数。关闭会取消待发送邮件，已经在发送的邮件仍可能到达。') }}</p></details>
      </section>
      <template v-if="data.usage">
        <p v-if="!data.configured" class="notice">{{ t('No budget saved yet. The figures below use the initial 100 USD budget for reference.','尚未保存预算，下方暂按初始 100 美元预算展示参考值。') }}</p>
        <div class="insight-metrics"><article class="panel"><span>{{ t('Known estimated cost','已知模型估算费用') }}</span><strong>{{ money(data.usage.known_cost) }}</strong></article><article class="panel"><span>{{ t('Saved monthly budget','已保存月预算') }}</span><strong>{{ data.configured?money(data.usage.monthly_budget):'—' }}</strong></article><article class="panel"><span>{{ t('Price coverage','价格覆盖率') }}</span><strong>{{ pct(data.usage.price_coverage) }}</strong></article><article class="panel"><span>{{ t('Projected month-end cost','预测月末费用') }}</span><strong>{{ money(data.usage.projected_cost) }}</strong></article></div>
        <section class="panel"><h2>{{ t('This month','本月情况') }}</h2><p>{{ date(data.usage.period_start) }} → {{ date(data.usage.period_end) }}</p><p>{{ t('As of','截至') }} {{ date(data.usage.as_of) }} · {{ t('Model calls','模型调用') }} {{ n(data.usage.generations) }} · {{ t('Known cost / budget','已知费用占预算') }} {{ data.usage.used_percent==null?'—':`${n(data.usage.used_percent)}%` }}</p>
          <p v-if="data.usage.price_coverage!=null&&Number(data.usage.price_coverage)<1" class="notice">{{ t('Some prices are missing. Known cost is only a subtotal; the projection is unavailable. An actual-budget warning can still fire when this subtotal reaches its threshold.','部分价格缺失，已知费用只是小计，暂不预测月末费用；若已知小计已达到提醒线，仍会发出实际费用提醒。') }} <RouterLink :to="`/app/settings?project=${project}`">{{ t('Configure prices','配置价格') }}</RouterLink></p>
          <p class="muted">{{ t('Projections require at least 3 elapsed days, 20 model calls, complete prices and full period retention. They extrapolate current spending over elapsed calendar time and do not predict future traffic. The saved budget is used for reminders; unsaved edits do not apply.','预测至少需要月初已过 3 天、20 次模型调用、价格完整且历史仍在保留期内。预测按当前费用与已过去时间线性推算，无法预知后续流量。提醒使用已保存预算，未保存修改不生效。') }}</p>
        </section>
      </template>
      <section class="panel"><h2>{{ t('Reminder history','提醒记录') }}</h2><p class="muted">{{ t('Up to 90 days within your plan retention. Values reflect the budget and known costs at generation time. Accepted means accepted by the mail service, not guaranteed inbox delivery.','可查看套餐保留范围内最近 90 天的记录。金额为生成提醒时的预算与已知费用；“邮件服务已接收”表示服务商已接受邮件，不保证收件箱送达。') }}</p><div class="insight-table"><table><thead><tr><th>{{ t('Reminder','提醒类型') }}</th><th>{{ t('Known cost','当时已知费用') }}</th><th>{{ t('Budget','当时预算') }}</th><th>{{ t('Status','状态') }}</th><th>{{ t('Generated (UTC)','生成时间（UTC）') }}</th></tr></thead><tbody><tr v-for="row in data.items" :key="row.id"><td>{{ kind(row.kind) }}</td><td>{{ money(row.known_cost) }}</td><td>{{ money(row.monthly_budget) }}</td><td>{{ state(row.status) }}</td><td>{{ date(row.created_at) }}</td></tr><tr v-if="!data.items.length"><td colspan="5" class="empty-cell">{{ t('No reminders generated.','尚未生成提醒。') }}</td></tr></tbody></table></div><QueryControls :page="page" :size="size" :total="data.total" :loading="busy" @change="page=$event;load()" @resize="size=$event;page=0;load()" /></section>
    </template>
  </section>
</template>
