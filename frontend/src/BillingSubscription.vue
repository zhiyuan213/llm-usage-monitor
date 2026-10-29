<script setup lang="ts">
import { useToastNotice } from "./toast";
import { ref, watch, onBeforeUnmount } from 'vue';
import { api, date, errorText, language, t } from './lib';
const props = defineProps<{ data: any; targetPlan: string; checkoutId: string }>();
const emit = defineEmits(['update:targetPlan', 'refresh']);
const quote = ref<any>(null), busy = ref(false), error = ref(''), notice = useToastNotice();
const pendingPlan = ref('');
let timer: ReturnType<typeof setTimeout> | undefined, attempts=0;
onBeforeUnmount(()=>clearTimeout(timer));
function poll() {
  clearTimeout(timer);
  if (++attempts > 20) {
    notice.value=t('Confirmation is taking longer than expected. Refresh Billing or check Manage subscription.','确认时间较长，请刷新账单或在“管理订阅”中核对状态。');
    return;
  }
  timer=setTimeout(()=>{emit('refresh'); poll();},2000);
}
watch(()=>props.checkoutId, id=>{
  if(!id) return;
  notice.value=t('Payment completed in checkout. Waiting for verified subscription confirmation.','收银台已完成，正在等待订阅通知验证确认。');
  attempts=0; poll();
},{immediate:true});
watch(()=>props.data, data=>{
  const w=data.workspace;
  const paid=props.checkoutId && (data.payments?.some((p:any)=>p.id===props.checkoutId && p.status==='completed') || data.confirmedCheckouts?.some((c:any)=>c.transaction_id===props.checkoutId));
  if (w?.subscription_id && w.subscription_status==='active' && (paid || (pendingPlan.value && w.plan===pendingPlan.value))) {
    clearTimeout(timer); pendingPlan.value='';
    notice.value=t('Subscription confirmed. Your current plan is ','订阅已确认，当前套餐为 ')+w.plan;
  }
},{deep:true});
function amount(totals: any, field = "total") {
  if (!totals?.currency_code || totals[field] == null) return t('None','无');
  const currency=totals.currency_code;
  const digits=new Intl.NumberFormat('en',{style:'currency',currency}).resolvedOptions().maximumFractionDigits ?? 2;
  return new Intl.NumberFormat(language.value,{style:'currency',currency}).format(Number(totals[field])/10**digits);
}
let previewVersion=0;
async function loadPreview(plan:string) {
  const version=++previewVersion;
  if(!plan) return;
  quote.value=null; error.value=''; busy.value=true;
  try { const result=await api('/billing/change-preview',{plan}); if(version===previewVersion) quote.value=result; }
  catch(e) { if(version===previewVersion) error.value=errorText(e); }
  finally { if(version===previewVersion) busy.value=false; }
}
watch(()=>props.targetPlan,loadPreview);
async function confirm() {
  if(busy.value || !quote.value) return;
  busy.value=true; error.value='';
  try {
    await api('/billing/change',{quoteId:quote.value.quoteId});
    pendingPlan.value=quote.value.plan; attempts=0; poll();
    emit('update:targetPlan',''); quote.value=null;
    notice.value=t('Change submitted. Waiting for verified billing confirmation.','变更已提交，正在等待账单通知验证确认。');
    emit('refresh');
  } catch(e) { error.value=errorText(e); quote.value=null; }
  finally { busy.value=false; }
}
</script>
<template>


  <section v-if="targetPlan" class="panel billing-subscription" aria-live="polite">
    <h2>{{t('Review plan change','确认套餐变更')}} → {{targetPlan}}</h2>
    <p v-if="busy">{{t('Please wait…','请稍候…')}}</p>
    <p v-if="error" class="notice error" role="alert">{{error}}</p>
    <button v-if="error && !busy" class="btn" @click="loadPreview(targetPlan)">{{t('Refresh preview','重新预览')}}</button>
    <template v-if="quote">
      <p>{{t('Immediate transaction','本次即时交易')}}: <strong>{{amount(quote.totals.immediate_transaction)}}</strong></p>
      <p v-if="Number(quote.totals.immediate_transaction?.credit || 0)">{{t('Credit applied now','本次使用余额抵扣')}}: {{amount(quote.totals.immediate_transaction, 'credit')}}</p>
      <p v-if="quote.totals.immediate_transaction?.balance != null">{{t('Amount due now','本次实际应付')}}: <strong>{{amount(quote.totals.immediate_transaction, 'balance')}}</strong></p>
      <p v-if="Number(quote.totals.immediate_transaction?.credit_to_balance || 0)">{{t('Credit added to balance','本次转入抵扣余额')}}: {{amount(quote.totals.immediate_transaction, 'credit_to_balance')}}</p>
      <p>{{t('Next transaction','下次交易')}}: {{amount(quote.totals.next_transaction)}}</p>
      <p v-if="Number(quote.totals.next_transaction?.credit || 0)">{{t('Credit applied next time','下次使用余额抵扣')}}: {{amount(quote.totals.next_transaction, 'credit')}}</p>
      <p v-if="quote.totals.next_transaction?.balance != null">{{t('Amount due next time','下次实际应付')}}: {{amount(quote.totals.next_transaction, 'balance')}}</p>
      <p>{{t('Recurring amount','后续周期金额')}}: {{amount(quote.totals.recurring_transaction_details)}}</p>
      <p class="muted">{{t('A negative amount is a Paddle credit, not a guaranteed bank refund. Re-preview if proration changes. Quote expires at','负数金额为 Paddle 抵扣，不代表银行退款；金额随剩余账期变化时需重新预览。预览到期时间')}} {{date(quote.expiresAt)}}</p>
      <button class="btn primary" :disabled="busy" @click="confirm">{{t('Confirm change','确认变更')}}</button>
    </template>
    <button class="btn" :disabled="busy" @click="emit('update:targetPlan','')">{{t('Close','关闭')}}</button>
  </section>
</template>
