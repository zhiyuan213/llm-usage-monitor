<script setup lang="ts">
import {ref,watch} from 'vue';
import {t} from './lib';
import {monthDates,rollingWindow,visibleDates,customWindow,inferWindowPolicy,type ReportWindow,type WindowPolicy} from './date-range';
const emit=defineEmits<{change:[value:ReportWindow,policy?:WindowPolicy]}>();
const props=defineProps<{busy?:boolean;initial?:string;value?:ReportWindow}>();
const period=ref(props.initial??'7');
const initialDates=period.value&&period.value!=='custom'?visibleDates(rollingWindow(Number(period.value))):monthDates();
const from=ref(initialDates.from),to=ref(initialDates.to),invalid=ref(false);
watch(()=>props.value,value=>{if(value){const dates=visibleDates(value);from.value=dates.from;to.value=dates.to;const policy=inferWindowPolicy(value);period.value=policy.live&&policy.days?String(policy.days):'custom';}},{immediate:true});
function choose(){
 invalid.value=false;
 if(period.value==='custom'){const dates=monthDates();from.value=dates.from;to.value=dates.to;}
 else if(period.value){const dates=visibleDates(rollingWindow(Number(period.value)));from.value=dates.from;to.value=dates.to;}
}
function query(){
 if(props.busy||!period.value)return;
 const value=period.value==='custom'?customWindow(from.value,to.value):rollingWindow(Number(period.value));
 invalid.value=!value;
 if(value){if(period.value!=='custom'){const dates=visibleDates(value);from.value=dates.from;to.value=dates.to;}emit('change',value,{live:period.value!=='custom'||to.value>=new Date().toISOString().slice(0,10),...(period.value!=='custom'?{days:Number(period.value)}:{})});}
}
function edit(){period.value='custom';invalid.value=false;}
</script>
<template><form class="insight-toolbar date-range-filter" :class="{'is-querying':busy}" :aria-busy="busy" @submit.prevent="query"><label>{{t('Date range (UTC)','日期范围（UTC）')}}<select v-model="period" :disabled="busy" @change="choose"><option value="" disabled>{{t('Choose a quick range','选择快捷日期')}}</option><option value="1">{{t('Last 24 hours','最近24小时')}}</option><option value="7">{{t('Last 7 days','最近7天')}}</option><option value="30">{{t('Last 30 days','最近30天')}}</option><option value="custom">{{t('Custom dates','自定义日期')}}</option></select></label><label>{{t('From','开始日期')}}<input type="date" v-model="from" :disabled="busy" @input="edit" /></label><label>{{t('Through','结束日期（含）')}}<input type="date" v-model="to" :disabled="busy" @input="edit" /></label><slot /><button type="submit" class="btn primary" :disabled="busy||!period">{{t('Search','查询')}}</button><span v-if="invalid" role="alert" class="error">{{t('Choose a valid date range.','请选择有效日期范围。')}}</span><slot name="actions" /></form></template>

