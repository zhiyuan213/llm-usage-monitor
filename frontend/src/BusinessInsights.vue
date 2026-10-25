<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { api, ApiError, errorText, t, date } from './lib';
import InsightResult from './InsightResult.vue';
import WeeklySummary from './WeeklySummary.vue';
import QueryControls from './QueryControls.vue';
const props=defineProps<{project:string;mode:string;refreshToken?:boolean}>();
const busy=ref(false),error=ref(''),notice=ref(''),locked=ref(false),report=ref<any>(null),items=ref<any[]>([]),total=ref(0),page=ref(0),size=ref(5);
const days=ref(7),dimension=ref('feature'),hours=ref(24),releaseId=ref(''),releaseName=ref(''),releasedAt=ref('');
const subscribed=ref(false),timezone=ref('UTC'),nextDue=ref(''),available=ref(true);
let version=0,alive=true;
onBeforeUnmount(()=>{alive=false;++version;});
const base=()=>`/projects/${props.project}/insights`;
async function load() {
  const id=++version;busy.value=true;error.value='';locked.value=false;
  try {
    if(props.mode==='cost-insights') {
      const result=await api(`${base()}/costs?days=${days.value}&dimension=${dimension.value}`);
      if(alive&&id===version)report.value=result;
    } else {
      const result=await api(`${base()}/${props.mode==='releases'?'releases':'weekly'}?page=${page.value}&size=${size.value}`);
      if(!alive||id!==version)return;
      items.value=result.items;total.value=result.total;
      if(props.mode==='weekly') {subscribed.value=result.subscription.enabled;timezone.value=result.subscription.timezone;nextDue.value=result.subscription.next_due||'';available.value=result.available;}
      if(page.value>0&&page.value*size.value>=result.total){page.value=Math.max(0,Math.ceil(result.total/size.value)-1);await load();}
    }
  }catch(e){if(alive&&id===version){locked.value=e instanceof ApiError&&e.code==='INSIGHTS_PLAN_REQUIRED';error.value=locked.value?'':errorText(e);}}
  finally{if(alive&&id===version)busy.value=false;}
}
watch([()=>props.project,()=>props.mode],()=>{page.value=0;report.value=null;releaseId.value='';items.value=[];notice.value='';void load();},{immediate:true});
watch(()=>props.refreshToken,(loading,previous)=>{if(previous&&!loading)void load();});
async function createRelease(){
  if(busy.value||!releaseName.value.trim()||!releasedAt.value)return;
  const id=++version;busy.value=true;error.value='';
  try {await api(`${base()}/releases`,{name:releaseName.value.trim(),releasedAt:new Date(releasedAt.value).toISOString()});if(alive&&id===version){releaseName.value='';releasedAt.value='';notice.value=t('Release marker saved. Select Compare to view its metrics.','发布标记已保存，可点击对比查看指标。');await load();}}
  catch(e){if(alive&&id===version)error.value=errorText(e);}finally{if(alive&&id===version)busy.value=false;}
}
async function compare(id=releaseId.value){
  if(busy.value||!id)return;const token=++version;busy.value=true;error.value='';report.value=null;releaseId.value=id;
  try {const result=await api(`${base()}/releases/${id}?hours=${hours.value}`);if(alive&&token===version)report.value=result;}
  catch(e){if(alive&&token===version)error.value=errorText(e);}finally{if(alive&&token===version)busy.value=false;}
}
async function remove(id:string){
  if(busy.value||!window.confirm(t('Delete this release marker? Reported calls will remain unchanged.','删除这条发布标记？已上报的调用记录不会受影响。')))return;
  const token=++version;busy.value=true;error.value='';
  try {await api(`${base()}/releases/${id}/delete`,{});if(alive&&token===version){report.value=null;releaseId.value='';await load();}}
  catch(e){if(alive&&token===version)error.value=errorText(e);}finally{if(alive&&token===version)busy.value=false;}
}
async function saveSubscription(enabled:boolean){
  if(busy.value)return;const token=++version;busy.value=true;error.value='';notice.value='';
  try{await api(`${base()}/weekly`,{enabled,timezone:timezone.value});if(alive&&token===version){notice.value=enabled?t('Weekly reports enabled for your account email.','已开启周报，将发送到你的账号邮箱。'):t('Weekly reports disabled. Pending deliveries have been cancelled.','已关闭周报，待发送邮件已取消。');await load();}}
  catch(e){if(alive&&token===version)error.value=errorText(e);}finally{if(alive&&token===version)busy.value=false;}
}
async function preview(){
  if(busy.value)return;const token=++version;busy.value=true;error.value='';report.value=null;
  try {const result=await api(`${base()}/weekly/example`);if(alive&&token===version)report.value=result;}
  catch(e){if(alive&&token===version)error.value=errorText(e);}finally{if(alive&&token===version)busy.value=false;}
}
async function saved(id:string){
  if(busy.value)return;const token=++version;busy.value=true;error.value='';report.value=null;
  try {const result=await api(`${base()}/weekly/reports/${id}`);if(alive&&token===version)report.value=result;}
  catch(e){if(alive&&token===version)error.value=errorText(e);}finally{if(alive&&token===version)busy.value=false;}
}
async function sendWeekly(){
  if(busy.value||!subscribed.value||!available.value)return;
  const token=++version;busy.value=true;error.value='';notice.value='';
  try {const result=await api(`${base()}/weekly/test`,{});if(alive&&token===version){notice.value=result.status==='queued'?t('Test email queued. It contains example data and does not affect your weekly schedule.','测试邮件已加入队列，内容为模拟数据，不影响正式周报和发送时间。'):t('A test email was requested recently. Please wait one minute before trying again.','刚刚已申请发送测试邮件，请间隔一分钟后再试。');page.value=0;await load();}}
  catch(e){if(alive&&token===version)error.value=errorText(e);}finally{if(alive&&token===version)busy.value=false;}
}
async function turn(next:number){page.value=next;await load();}
async function resize(next:number){size.value=next;page.value=0;await load();}
const delivery=(value:string)=>({sent:t('Sent','已发送'),queued:t('Waiting to send','等待发送'),failed:t('Delivery failed','发送失败'),cancelled:t('Cancelled','已取消')}[value]||value);
</script>
<template>
  <section class="business-insights" :aria-busy="busy">
    <div v-if="error" class="notice error" role="alert">{{ error }}</div><div v-if="notice" class="notice success" role="status">{{ notice }}</div>
    <section v-if="locked" class="panel"><h2>{{ t('Available with Core and Pro','Core 与 Pro 可使用') }}</h2><p>{{ t('Diagnose model costs, compare releases and subscribe to weekly reports.','诊断模型费用、对比发布前后表现，并订阅运营周报。') }}</p><RouterLink class="btn primary" to="/app/billing">{{ t('View plans','查看套餐') }}</RouterLink></section>
    <template v-else>
      <section v-if="mode==='cost-insights'" class="panel insight-toolbar"><label>{{ t('Period','统计区间') }}<select v-model.number="days" :disabled="busy" @change="report=null;load()"><option :value="1">{{ t('Last 24 hours','最近 24 小时') }}</option><option :value="7">{{ t('Last 7 days','最近 7 天') }}</option><option :value="14">{{ t('Last 14 days','最近 14 天') }}</option></select></label><label>{{ t('Group by','费用分组') }}<select v-model="dimension" :disabled="busy" @change="report=null;load()"><option value="feature">{{ t('Feature','功能') }}</option><option value="model">{{ t('Model','模型') }}</option><option value="customerId">{{ t('Customer','客户') }}</option></select></label><button class="btn" :disabled="busy" @click="load">{{ t('Refresh analysis','刷新诊断') }}</button></section>
      <template v-if="mode==='releases'">
        <section class="panel"><h2>{{ t('Record a release','记录一次发布') }}</h2><p>{{ t('This only marks a time for comparison; it does not deploy your application. The selected local time is converted to UTC.','这里仅记录对比时间，不会部署你的应用。选择的本地时间会转换为 UTC。') }}</p><form class="insight-toolbar" @submit.prevent="createRelease"><label>{{ t('Release name','发布名称') }}<input v-model="releaseName" required maxlength="80" :disabled="busy" :placeholder="t('e.g. Support bot v2','例如：客服助手 v2')" /></label><label>{{ t('Released at (your local time)','发布时间（你的本地时间）') }}<input v-model="releasedAt" required type="datetime-local" :disabled="busy" /></label><button class="btn primary" :disabled="busy||!releaseName.trim()||!releasedAt">{{ t('Save release','保存发布标记') }}</button></form></section>
        <section class="panel"><div class="insight-toolbar"><h2>{{ t('Release history','发布记录') }}</h2><label>{{ t('Window on each side','发布前后各取') }}<select v-model.number="hours" :disabled="busy" @change="releaseId&&compare()"><option :value="24">24 {{ t('hours','小时') }}</option><option :value="72">72 {{ t('hours','小时') }}</option><option :value="168">7 {{ t('days','天') }}</option></select></label></div><div class="insight-table"><table><thead><tr><th>{{ t('Release','发布') }}</th><th>{{ t('Released at (UTC)','发布时间（UTC）') }}</th><th>{{ t('Actions','操作') }}</th></tr></thead><tbody><tr v-for="row in items" :key="row.id"><td>{{ row.name }}</td><td>{{ date(row.released_at) }}</td><td class="insight-actions"><button class="text-button" :disabled="busy" @click="compare(row.id)">{{ t('Compare','对比') }}</button><button class="text-button danger" :disabled="busy" @click="remove(row.id)">{{ t('Delete marker','删除标记') }}</button></td></tr><tr v-if="!items.length"><td colspan="3" class="empty-cell">{{ t('No release markers yet.','暂无发布标记。') }}</td></tr></tbody></table></div><QueryControls :page="page" :size="size" :total="total" :loading="busy" @change="turn" @resize="resize" /></section>
        <p v-if="report&&!report.window_complete" class="notice">{{ t('The full post-release window is not available yet. Both sides use the same elapsed duration so far; this is a preliminary comparison.','发布后的完整窗口尚未结束。当前前后两侧取相同的已过去时长，这是初步对比。') }}</p><p v-if="report" class="muted">{{ t('A before/after change does not prove the release caused it. Traffic, model mix and prices may also change.','前后变化不能证明由本次发布导致，流量、模型组合和价格也可能变化。') }}</p>
      </template>
      <template v-if="mode==='weekly'">
        <section class="panel"><h2>{{ t('Weekly email report','自动运营周报') }}</h2><p>{{ t('Optional. Every Monday at 09:00 in the selected timezone, receive the previous completed calendar week’s model cost and request summary at your verified account email. No prompts or responses are emailed.','自愿订阅。开启后，每周一按所选时区上午 09:00，将上一个完整自然周的模型费用和请求摘要发送到已验证的账号邮箱，不发送提示词或回答正文。') }}</p><div class="insight-toolbar"><label>{{ t('Timezone','时区') }}<input v-model="timezone" :disabled="busy||!available" list="insight-timezones" placeholder="Asia/Shanghai" /><datalist id="insight-timezones"><option>UTC</option><option>Asia/Shanghai</option><option>America/New_York</option><option>Europe/London</option></datalist></label><button class="btn primary" :disabled="busy||!available" @click="saveSubscription(true)">{{ subscribed?t('Save subscription','保存订阅'):t('Enable weekly reports','开启周报') }}</button><button v-if="subscribed" class="btn" :disabled="busy" @click="saveSubscription(false)">{{ t('Disable weekly reports','关闭周报') }}</button><button class="btn" :disabled="busy" @click="preview">{{ t('View report example','查看周报示例') }}</button><button class="btn" :disabled="busy||!available||!subscribed" @click="sendWeekly">{{ t('Send test email','发送测试邮件') }}</button></div><p class="muted">{{ t('The example uses synthetic data. Test emails verify delivery and layout without generating your actual weekly report. Allow one minute between tests.','示例使用模拟数据。测试邮件仅用于检查收件和内容格式，不会生成正式周报；每次测试请间隔一分钟。') }}</p><p>{{ subscribed?t('Enabled','已开启'):t('Disabled','未开启') }}<span v-if="subscribed&&nextDue"> · {{ t('Next scheduled (UTC)','下次计划发送（UTC）') }}：{{ date(nextDue) }}</span></p><p v-if="!available" class="notice">{{ t('Core or Pro is required to enable reports. You can still disable an existing subscription.','开启周报需要 Core 或 Pro 套餐，已有订阅仍可关闭。') }} <RouterLink to="/app/billing">{{ t('View plans','查看套餐') }}</RouterLink></p><p class="muted">{{ t('Generation runs shortly after the scheduled time. Mail delivery may be delayed or fail; see status below. An email already being sent may still arrive after you disable reports.','周报会在计划时间后由定时任务生成。邮件可能延迟或失败，请查看下方状态；关闭时已经在发送的邮件仍可能到达。') }}</p></section>
        <section class="panel"><h2>{{ t('Delivery history','发送记录') }}</h2><p class="muted">{{ t('Reports are available for up to 90 days within your plan retention. Sent means the mail service accepted the message; inbox delivery is not guaranteed.','可查看套餐保留范围内、最近 90 天的周报。“已发送”表示邮件服务已接收，不保证收件箱送达。') }}</p><div class="insight-table"><table><thead><tr><th>{{ t('Week ended (UTC)','周结束时间（UTC）') }}</th><th>{{ t('Status','状态') }}</th><th>{{ t('Generated (UTC)','生成时间（UTC）') }}</th></tr></thead><tbody><tr v-for="row in items" :key="row.id"><td><button class="text-button" :disabled="busy" @click="saved(row.id)">{{ row.test_email?t('Test email · example data','测试邮件 · 模拟数据'):date(row.week_end) }}</button></td><td>{{ delivery(row.status) }}</td><td>{{ date(row.created_at) }}</td></tr><tr v-if="!items.length"><td colspan="3" class="empty-cell">{{ t('No reports generated yet.','尚未生成周报。') }}</td></tr></tbody></table></div><QueryControls :page="page" :size="size" :total="total" :loading="busy" @change="turn" @resize="resize" /></section>
      </template>
      <p v-if="busy" role="status">{{ t('Loading…','正在加载…') }}</p><p v-if="report?.archived" class="notice">{{ t('Saved report snapshot. Later corrections or deletions do not recalculate this historical report.','这是生成时保存的周报快照，后续更正或删除不会重新计算历史周报。') }}</p><WeeklySummary v-if="report&&mode==='weekly'" :report="report" :project="project" /><InsightResult v-else-if="report" :report="report" :project="project" />
    </template>
  </section>
</template>
