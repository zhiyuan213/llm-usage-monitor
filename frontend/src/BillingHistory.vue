<script setup lang="ts">
import {usePageSize} from "./page-size";
import { ref, watch } from 'vue';
import { api, t, date, errorText } from './lib';
import QueryControls from './QueryControls.vue';
const props=defineProps<{ kind:'payments'|'adjustments'; workspace:any }>();
const rows=ref<any[]>([]), total=ref(0), page=ref(0), size=usePageSize(), busy=ref(false), error=ref('');
let generation=0;
async function load() {
  const current=++generation; busy.value=true; error.value='';
  try {
    const result=await api(`/billing/history?kind=${props.kind}&page=${page.value}&size=${size.value}`);
    if(current!==generation)return;
    total.value=result.total; rows.value=result.items;
    const last=Math.max(0,Math.ceil(total.value/size.value)-1);
    if(page.value>last){page.value=last;await load();}
  } catch(e){if(current===generation)error.value=errorText(e);}
  finally{if(current===generation)busy.value=false;}
}
watch(()=>props.workspace,load,{immediate:true});
function label(value:string){
  const labels:Record<string,[string,string]>={completed:['Completed','已完成'],refund:['Refund','退款'],credit:['Credit','抵扣'],approved:['Approved','已批准'],pending_approval:['Pending approval','待审核'],rejected:['Rejected','已拒绝'],issued:['Issued','已发放']};
  return labels[value]?t(...labels[value]):value;
}
</script>
<template>
  <section class="panel billing-history-panel">
    <div class="panel-heading"><h2>{{kind==='payments'?t('Billing history','账单记录'):t('Refunds and credits','退款与抵扣')}}</h2></div>
    <p v-if="error" class="notice error" role="alert">{{error}}</p>
    <div class="table-wrap"><table><thead><tr><th>{{t('Transaction','交易')}}</th><th v-if="kind==='adjustments'">{{t('Action','动作')}}</th><th>{{t('Status','状态')}}</th><th>{{t('Amount','金额')}}</th><th>{{t('Time (UTC)','时间（UTC）')}}</th></tr></thead>
      <tbody><tr v-for="row in rows" :key="row.id"><td>{{kind==='payments'?row.id:row.transaction_id}}<template v-if="kind==='adjustments'"><br><small>{{row.id}}</small></template></td><td v-if="kind==='adjustments'">{{label(row.action)}}</td><td>{{label(row.status)}}</td><td>{{row.amount}} {{row.currency}}</td><td>{{date(kind==='payments'?row.created_at:row.updated_at)}}</td></tr>
      <tr v-if="!rows.length"><td :colspan="kind==='payments'?4:5" class="empty-cell">{{busy?t('Loading…','加载中…'):t('No records.','暂无记录。')}}</td></tr></tbody>
    </table></div>
    <QueryControls :page="page" :size="size" :total="total" :loading="busy" @change="page=$event;load()" @resize="size=$event;page=0;load()" />
  </section>
</template>
