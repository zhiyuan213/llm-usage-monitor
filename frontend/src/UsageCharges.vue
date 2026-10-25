<script setup lang="ts">
import { ref, watch } from 'vue';
import { api, t, money, date, errorText, n } from './lib';
import QueryControls from './QueryControls.vue';
const props=defineProps<{ workspace:any }>();
const data=ref<any>(null), consent=ref(false), busy=ref(false), error=ref('');
const page=ref(0), size=ref(5), loading=ref(false);
let version=0;
async function load(){const current=++version;loading.value=true;error.value='';try{const result=await api(`/billing/usage-charges?page=${page.value}&size=${size.value}`);if(current!==version)return;data.value=result;const last=Math.max(0,Math.ceil((result.total||0)/size.value)-1);if(page.value>last){page.value=last;await load();}}catch(e){if(current===version)error.value=errorText(e);}finally{if(current===version)loading.value=false;}}
watch(()=>props.workspace,load,{immediate:true});
async function buy(){busy.value=true;error.value='';try{const result=await api('/billing/addons',{consent:consent.value});location.assign(result.url);}catch(e){error.value=errorText(e);}finally{busy.value=false;}}
function state(c:any){
 if(c.revoked)return t('Refunded — units removed','已退款，额度已撤回');
 if(c.status==='paid')return c.kind==='addon'?(c.credited?t('Paid — units added','已付款，额度已到账'):t('Paid','已付款')):t('Paid','已付款');
 return ({ready:t('Awaiting collection','待扣款'),processing:t('Confirming','确认中'),submitted:t('Awaiting payment confirmation','等待付款确认'),unknown:t('Payment result needs reconciliation','付款结果待核对'),failed:t('Payment failed','付款失败'),refund_required:t('Paid after expiry — refund review required','到账时已过期，待退款处理')} as any)[c.status]||c.status;
}
</script>
<template>
<section class="panel usage-charges-panel">
 <div class="panel-heading"><h2>{{t('Additional units & usage charges','加购额度与超额账单')}}</h2><button class="btn" :disabled="loading" @click="load">{{t('Refresh','刷新')}}</button></div>
 <p>{{t('One pack adds 100,000 units for USD 8, valid only in the current billing period. Covers future usage only. No automatic renewal. Units arrive after verified payment. Any approved pack refund removes its units.','每包 100,000 units，8 美元，仅当前账期有效，仅抵扣后续新增用量，不自动续购。付款确认后到账；加购包退款获批后撤回该包额度。')}}</p>
 <p v-if="workspace?.payment_provider==='creem'" class="notice">{{t('Additional usage billing is not enabled for Creem subscriptions. Ingestion stops at the included quota.','Creem 订阅暂未开放加购及自动超额计费，额度用完后停止新增接入。')}}</p>
 <p v-else>{{t('Authorized overage is settled after the period closes, using progressive rates and your cap. An inactive subscription requires checkout payment instead of charging its saved method. Prices include tax; Paddle confirms the final payment and any credit applied.','已授权的超额费用在账期结束后按阶梯价和上限结算；订阅已取消或逾期时改为收银台付款。价格含税，最终付款与余额抵扣以 Paddle 确认为准。')}}</p>
 <p v-if="!data?.enabled" class="notice">{{t('Additional billing is not enabled by the operator yet.','运营方尚未启用加购及超额扣款。')}}</p>
 <form @submit.prevent="buy" class="usage-pack-form">
  <label class="check-label"><input type="checkbox" v-model="consent" :disabled="!data?.enabled" />{{t('I agree to buy one pack for USD 8. Unused units expire at the period end.','我同意支付 8 美元购买一包，未用额度在账期结束时到期。')}}</label>
  <button class="btn primary" :disabled="busy || !consent || !data?.enabled || workspace?.plan==='Free' || workspace?.subscription_status!=='active'">{{t('Buy one pack','购买一包')}}</button>
 </form>
 <dl class="quota-breakdown"><dt>{{t('Plan quota','套餐额度')}}</dt><dd>{{n(workspace?.plan==='Free'?50000:100000)}} units</dd><dt>{{t('Purchased quota this period','本期加购额度')}}</dt><dd>{{n(workspace?.addon_units||0)}} units</dd><dt>{{t('Total used this period','本期已用额度')}}</dt><dd>{{n(workspace?.used_units||0)}} units</dd><dt>{{t('Remaining plan + pack quota','套餐与加购总剩余额度')}}</dt><dd>{{n(data?.availableUnits)}} units</dd></dl>
 <p class="muted">{{t('Remaining quota includes only plan and purchased units, not an authorized overage spending cap. Expired or inactive subscriptions cannot use remaining units.','总剩余额度仅含套餐与加购额度，不包含授权超额费用上限；订阅到期或失效后停止使用剩余额度。')}}</p>
 <p v-if="error" role="alert" class="error">{{error}}</p>
 <div v-if="data?.charges?.length" class="table-wrap"><table><thead><tr><th>{{t('Type','类型')}}</th><th>{{t('Amount','金额')}}</th><th>{{t('Status','状态')}}</th><th>{{t('Period end','账期结束')}}</th></tr></thead><tbody><tr v-for="c in data.charges" :key="c.id"><td>{{c.kind==='addon'?t('100,000-unit pack','10万 units 加购包'):t('Period overage','账期超额')}}</td><td>{{money(c.amount)}}</td><td>{{state(c)}} <a v-if="c.checkout_url && c.status==='submitted'" :href="c.checkout_url">{{t('Continue payment','继续付款')}}</a></td><td>{{date(c.period_end)}}</td></tr></tbody></table></div>
 <p v-else>{{t('No additional charges.','暂无加购或超额账单。')}}</p>
 <QueryControls :page="page" :size="size" :total="data?.total || 0" :loading="loading" @change="page=$event;load()" @resize="size=$event;page=0;load()" />
</section>
</template>
