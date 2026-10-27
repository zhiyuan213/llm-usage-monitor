<script setup lang="ts">
import {ref,watch} from 'vue';
import {t} from './lib';
import {monthDates,rollingWindow,visibleDates,customWindow,type ReportWindow} from './date-range';
const emit=defineEmits<{change:[value:ReportWindow]}>();
const props=defineProps<{busy?:boolean;initial?:string;value?:ReportWindow}>();
const period=ref(props.initial??'7');
const initialDates=period.value&&period.value!=='custom'?visibleDates(rollingWindow(Number(period.value))):monthDates();
const from=ref(initialDates.from),to=ref(initialDates.to),invalid=ref(false);
watch(()=>props.value,value=>{if(value){const dates=visibleDates(value);from.value=dates.from;to.value=dates.to;const days=(Date.parse(value.to)-Date.parse(value.from))/86400000;period.value=[1,7,30].includes(days)?String(days):'custom';}},{immediate:true});
function choose(){
 invalid.value=false;
 if(period.value==='custom'){const dates=monthDates();from.value=dates.from;to.value=dates.to;}
 else if(period.value)query();
}
function query(){
 const value=period.value==='custom'?customWindow(from.value,to.value):rollingWindow(Number(period.value));
 invalid.value=!value;
 if(value){if(period.value!=='custom'){const dates=visibleDates(value);from.value=dates.from;to.value=dates.to;}emit('change',value);}
}
function edit(){period.value='custom';invalid.value=false;}
</script>
<template><div class="insight-toolbar date-range-filter"><label>{{t('Date range (UTC)','日期范围（UTC）')}}<select v-model="period" :disabled="busy" @change="choose"><option value="" disabled>{{t('Choose a quick range','选择快捷日期')}}</option><option value="1">{{t('Last 24 hours','最近24小时')}}</option><option value="7">{{t('Last 7 days','最近7天')}}</option><option value="30">{{t('Last 30 days','最近30天')}}</option><option value="custom">{{t('Custom dates','自定义日期')}}</option></select></label><label>{{t('From','开始日期')}}<input type="date" v-model="from" :disabled="busy" @input="edit" /></label><label>{{t('Through','结束日期（含）')}}<input type="date" v-model="to" :disabled="busy" @input="edit" /></label><button v-if="period==='custom'" type="button" class="btn" :disabled="busy" @click="query">{{t('Apply dates','查询日期')}}</button><span v-if="invalid" role="alert" class="error">{{t('Choose a valid date range.','请选择有效日期范围。')}}</span></div></template>
