<script setup lang="ts">
import {t} from './lib';
const rules=defineModel<any[]>({required:true});
function add(){rules.value=[...rules.value,{batch:false,serviceTier:'standard',region:'global',minInputTokens:0,inputRate:0,outputRate:0,cachedRate:0}];}
</script>
<template>
 <details class="billing-price-rules price-band-toggle"><summary>{{t('Special billing rates','特殊计费规则')}}</summary>
  <p>{{t('Use explicit USD rates per million tokens. The highest matching input threshold applies to the whole call. No automatic discounts or currency conversion. Do not combine with time-of-day rates.','填写明确的美元/百万 Token 费率。匹配最高输入门槛后，整次调用使用该档费率。不自动套用折扣或换汇，请勿与分时费率混用。')}}</p>
  <fieldset v-for="(r,i) in rules" :key="i"><legend>{{t('Rate card','费率规则')}} {{i+1}}</legend><div class="billing-rule-grid">
   <label>{{t('Batch API','供应商 Batch API')}}<select v-model="r.batch"><option :value="false">{{t('No','否')}}</option><option :value="true">{{t('Yes','是')}}</option></select></label>
   <label>{{t('Service tier','服务等级')}}<input v-model="r.serviceTier" required maxlength="64" placeholder="standard" /></label>
   <label>{{t('Region','计费区域')}}<input v-model="r.region" required maxlength="64" placeholder="global" /></label>
   <label>{{t('Input threshold (inclusive)','输入门槛（包含）')}}<input type="number" v-model.number="r.minInputTokens" min="0" step="1" required /></label>
   <label v-for="(label,key) in {inputRate:t('Input','输入'),outputRate:t('Output','输出'),cachedRate:t('Cache read','缓存读取'),cacheWrite5mRate:t('Cache write 5m','缓存写入5分钟'),cacheWrite1hRate:t('Cache write 1h','缓存写入1小时')}" :key="key">{{label}}<input type="number" min="0" step="any" :value="r[key]" @input="r[key]=($event.target as HTMLInputElement).value===''?undefined:Number(($event.target as HTMLInputElement).value)" :required="['inputRate','outputRate','cachedRate'].includes(key)" /></label>
  </div><button type="button" class="btn" @click="rules=rules.filter((_,n)=>n!==i)">{{t('Remove rule','移除规则')}}</button></fieldset>
  <button type="button" class="btn" :disabled="rules.length>=64" @click="add">{{t('Add billing rule','添加计费规则')}}</button>
 </details>
</template>
<style scoped>
.billing-price-rules{grid-column:1/-1;min-width:0}.billing-price-rules summary{cursor:pointer;color:#3156e8}.billing-price-rules fieldset{border:1px solid #e0e6f2;border-radius:8px;margin:12px 0;padding:16px;min-width:0}.billing-rule-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-bottom:12px}.billing-rule-grid label{display:flex;flex-direction:column;gap:6px}.billing-rule-grid input,.billing-rule-grid select{width:100%;min-width:0}@media(max-width:760px){.billing-rule-grid{grid-template-columns:1fr}}
</style>
