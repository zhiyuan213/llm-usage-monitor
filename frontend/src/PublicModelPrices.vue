<script setup lang="ts">
import {onMounted,ref,watch,onBeforeUnmount} from 'vue';
import {api,errorText,t,language} from './lib';
import {usePageSize} from './page-size';
import QueryControls from './QueryControls.vue';
import {publicPriceRate} from './public-price-display';
const props=defineProps<{project:string;refreshKey?:unknown;publicPricing?:boolean}>();
const query=ref(''),provider=ref(''),applied=ref({q:'',provider:''}),page=ref(0),size=usePageSize();
const result=ref<any>({items:[],providers:[],total:0}),busy=ref(false),error=ref('');
let requestId=0;
const rate=(value:unknown)=>publicPriceRate(value,language.value);
const date=(value:string)=>value?new Date(value).toISOString().replace('T',' ').replace(/\.\d{3}Z$/,' UTC'):'—';
async function load(){
 const id=++requestId;busy.value=true;error.value='';
 try{
  const params=new URLSearchParams({q:applied.value.q,provider:applied.value.provider,page:String(page.value),size:String(size.value)});
  const response=await api(`/projects/${props.project}/public-prices?${params}`);
  if(id!==requestId)return;
  result.value=response;
  if(page.value>0&&page.value*size.value>=response.total){page.value=Math.max(0,Math.ceil(response.total/size.value)-1);await load();}
 }catch(e){if(id===requestId){error.value=errorText(e);result.value={...result.value,items:[],total:0};}}
 finally{if(id===requestId)busy.value=false;}
}
function search(){applied.value={q:query.value.trim(),provider:provider.value};page.value=0;void load();}
function resize(value:number){size.value=value;page.value=0;void load();}
watch(()=>props.project,()=>{query.value='';provider.value='';applied.value={q:'',provider:''};page.value=0;result.value={items:[],providers:[],total:0};void load();});
watch(()=>props.refreshKey,()=>void load());
onMounted(load);onBeforeUnmount(()=>{requestId++;});
</script>
<template>
 <section class="panel public-model-prices" :aria-label="t('Public model prices','公共模型价格表')">
  <h3>{{t('Public model prices','公共模型价格表')}}</h3>
  <p class="muted">{{t('Current effective public base rates in USD per million tokens. Project overrides take priority; historical and future versions are excluded. Expand a model to inspect its source and time-of-day rates, which override the base rates during their periods.','当前已生效的公共基础费率，单位为美元/百万 Token。项目自定义价格优先；不展示历史或尚未生效版本。展开模型可查看来源和分时费率，对应时段使用分时费率。')}}</p>
  <p v-if="publicPricing===false" class="muted">{{t('Public price matching is disabled for this project. This table is for reference only.','此项目已关闭公共价格匹配，下表仅供参考。')}}</p>
  <form class="public-price-search" @submit.prevent="search">
   <label>{{t('Provider','供应商')}}<select v-model="provider" :disabled="busy"><option value="">{{t('All providers','全部供应商')}}</option><option v-for="p in result.providers" :key="p" :value="p">{{p}}</option></select></label>
   <label>{{t('Search models','搜索模型')}}<input v-model="query" maxlength="128" :placeholder="t('Provider or model name','供应商或模型名称')" /></label>
   <button class="btn primary" :disabled="busy">{{t('Search','查询')}}</button>
  </form>
  <p v-if="error" role="alert" class="error">{{error}} <button class="btn" @click="load" :disabled="busy">{{t('Retry','重试')}}</button></p>
  <div class="table-wrap" :aria-busy="busy">
   <table>
    <thead><tr><th>{{t('Provider / model','供应商 / 模型')}}</th><th>{{t('Input / 1M','输入 / 百万')}}</th><th>{{t('Output / 1M','输出 / 百万')}}</th><th>{{t('Cached input / 1M','缓存输入 / 百万')}}</th><th>{{t('Effective at (UTC)','生效时间（UTC）')}}</th></tr></thead>
    <tbody>
     <tr v-for="p in result.items" :key="p.id">
      <td><details><summary>{{p.provider}} / {{p.model}} <span v-if="p.custom_override" class="badge">{{t('Custom override','自定义覆盖')}}</span></summary>
       <div class="public-price-details"><p>{{t('Source','来源')}}：{{p.source}}</p><p>{{t('Fetched at (UTC)','获取时间（UTC）')}}：{{date(p.fetched_at)}}</p>
        <p v-if="!p.time_bands.length">{{t('No time-of-day rules in this price version.','此价格版本无分时规则。')}}</p>
        <p v-for="(band,index) in p.time_bands" :key="index">{{band.timezone}} · {{band.start}}–{{band.end}} · {{t('Input / output / cached','输入 / 输出 / 缓存')}}：{{rate(band.inputRate)}} / {{rate(band.outputRate)}} / {{rate(band.cachedRate)}}</p>
        <p v-for="(rule,index) in p.billing_rules||[]" :key="`billing-${index}`">{{rule.batch?'Batch':'Standard'}} · {{rule.serviceTier}} · {{rule.region}} · {{t('Input ≥','输入 ≥')}} {{rule.minInputTokens}} · {{t('Input / output / cache read / write 5m / write 1h','输入 / 输出 / 缓存读取 / 写入5分钟 / 写入1小时')}}：{{rate(rule.inputRate)}} / {{rate(rule.outputRate)}} / {{rate(rule.cachedRate)}} / {{rule.cacheWrite5mRate==null?t('Unknown','未知'):rate(rule.cacheWrite5mRate)}} / {{rule.cacheWrite1hRate==null?t('Unknown','未知'):rate(rule.cacheWrite1hRate)}}</p>
       </div></details></td>
      <td>{{rate(p.input_rate)}}</td><td>{{rate(p.output_rate)}}</td><td>{{p.cached_known?rate(p.cached_rate):t('Unknown','未知')}}</td><td>{{date(p.effective_at)}}</td>
     </tr>
     <tr v-if="!result.items.length"><td colspan="5" class="empty-cell">{{busy?t('Loading prices…','正在加载价格…'):error?t('Prices could not be loaded.','价格加载失败。'):t('No matching public prices. The catalogue may not have synced yet.','暂无匹配的公共价格，目录可能尚未同步。')}}</td></tr>
    </tbody>
   </table>
  </div>
  <QueryControls :page="page" :size="size" :total="result.total" :loading="busy" @change="page=$event;load()" @resize="resize" />
 </section>
</template>
