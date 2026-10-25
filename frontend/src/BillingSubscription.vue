<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue';
import { api, date, errorText, language, t } from './lib';
const props = defineProps<{ data: any; targetPlan: string; checkoutId: string }>();
const emit = defineEmits(['update:targetPlan', 'refresh']);
const quote = ref<any>(null), busy = ref(false), error = ref(''), notice = ref('');
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
  const paid=props.checkoutId && data.payments?.some((p:any)=>p.id===props.checkoutId && p.status==='completed');
  if (w?.subscription_id && w.subscription_status==='active' && (paid || (pendingPlan.value && w.plan===pendingPlan.value))) {
    clearTimeout(timer); pendingPlan.value='';
    notice.value=t('Subscription confirmed. Your current plan is ','订阅已确认，当前套餐为 ')+w.plan;
  }
},{deep:true});
function status(value: string) {
  const labels: Record<string, [string,string]> = {
    cancel:['Cancel','取消'], pause:['Pause','暂停'], active:['Active','活跃'], past_due:['Past due','付款逾期'], canceled:['Canceled','已取消'],
    paused:['Paused','已暂停'], trialing:['Trialing','试用中'], approved:['Approved','已批准'],
    pending_approval:['Pending approval','待审核'], rejected:['Rejected','已拒绝'],
    refund:['Refund','退款'], credit:['Credit','抵扣'], completed:['Completed','已完成'],
  };
  return labels[value] ? t(...labels[value]) : value;
}
function amount(totals: any, field = "total") {
  if (!totals?.currency_code || totals[field] == null) return t('None','无');
  const currency=totals.currency_code;
  const digits=new Intl.NumberFormat('en',{style:'currency',currency}).resolvedOptions().maximumFractionDigits ?? 2;
  return new Intl.NumberFormat(language.value,{style:'currency',currency}).format(Number(totals[field])/10**digits);
}
function scheduled() {
  const raw=props.data.workspace?.scheduled_change;
  if (!raw) return null;
  try { return typeof raw === 'string' ? JSON.parse(raw) : raw; } catch { return null; }
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
  <section v-if="data.workspace?.subscription_id" class="panel billing-subscription">
    <h2>{{t('Subscription status','订阅状态')}}</h2>
    <p><strong>{{status(data.workspace.subscription_status)}}</strong> · {{data.workspace.plan}}</p>
    <p v-if="scheduled()">{{t('Scheduled change','计划变更')}}: {{status(scheduled().action)}} · {{date(scheduled().effective_at)}}</p>
    <p v-if="data.workspace.subscription_status !== 'active'" class="notice error">{{t('This subscription is not active. Review payment or cancellation details in Manage subscription.','此订阅当前未处于活跃状态，请在“管理订阅”查看付款或取消详情。')}}</p>
    <p v-if="data.workspace.plan !== 'Free' && data.workspace.retention_grace_until" class="notice" role="status">{{t('Renewal grace ends at','续费宽限期截止')}} {{date(data.workspace.retention_grace_until)}}. {{t('New ingestion is stopped. After this deadline, Free retention applies and analytics older than 30 days will be deleted.','当前停止新增接入。期满后按 Free 保留规则清理超过30天的调用数据。')}}</p>
    <p class="muted">{{t('Core / Pro changes apply immediately with Paddle proration. Review the amount before confirming; failed payments prevent the change. Current-period usage is preserved.','Core / Pro 立即切换，由 Paddle 按剩余账期折算差价。确认前请核对金额；付款失败不变更套餐，当前账期已用额度不会清零。')}}</p>
  </section>
  <p v-if="notice" class="notice" role="status">{{notice}}</p>
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
