<script setup lang="ts">
import {ref} from 'vue';
import {t} from './lib';
const emit=defineEmits<{change:[value:{from:string;to:string}]}>();
const props=defineProps<{busy?:boolean;initial?:string}>();
const period=ref(props.initial??'7'),from=ref(''),to=ref(''),invalid=ref(false);
function query(){
 const end=period.value==='custom'?new Date(to.value+'T00:00:00Z'):new Date();
 const start=period.value==='custom'?new Date(from.value+'T00:00:00Z'):new Date(end.getTime()-Number(period.value)*86400000);
 if(period.value==='custom'){end.setUTCDate(end.getUTCDate()+1);if(end.getTime()>Date.now())end.setTime(Date.now());}
 invalid.value=!Number.isFinite(start.getTime())||!Number.isFinite(end.getTime())||start>=end;
 if(!invalid.value)emit('change',{from:start.toISOString(),to:end.toISOString()});
}
</script>
<template><div class="insight-toolbar date-range-filter"><label>{{t('Date range (UTC)','日期范围（UTC）')}}<select v-model="period" :disabled="busy" @change="period!=='custom' && query()"><option value="" disabled>{{t('Choose a quick range','选择快捷日期')}}</option><option value="1">{{t('Last 24 hours','最近24小时')}}</option><option value="7">{{t('Last 7 days','最近7天')}}</option><option value="30">{{t('Last 30 days','最近30天')}}</option><option value="custom">{{t('Custom dates','自定义日期')}}</option></select></label><template v-if="period==='custom'"><label>{{t('From','开始日期')}}<input type="date" v-model="from" :disabled="busy" /></label><label>{{t('Through','结束日期（含）')}}<input type="date" v-model="to" :disabled="busy" /></label></template><button v-if="period==='custom'" type="button" class="btn" :disabled="busy||!period" @click="query">{{t('Apply dates','查询日期')}}</button><span v-if="invalid" role="alert" class="error">{{t('Choose a valid date range.','请选择有效日期范围。')}}</span></div></template>
