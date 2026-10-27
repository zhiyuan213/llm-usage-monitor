<script setup lang="ts">
import {computed} from 'vue';
import {t} from './lib';
import {policies,policyDate,policyContact} from './policy-content';
const props=defineProps<{page:string}>();
const policy=computed(()=>policies[props.page]??policies.terms);
</script>
<template><main class="legal public-content"><span class="eyebrow">METERTRAIL</span><h1>{{t(...policy.title)}}</h1><p>{{t('Effective and last updated:','生效及最后更新日期：')}} {{policyDate}}</p><section v-for="section in policy.sections" :key="section.heading[0]"><h2>{{t(...section.heading)}}</h2><p v-for="paragraph in section.paragraphs" :key="paragraph[0]">{{t(...paragraph)}}</p></section><p><a :href="'mailto:'+policyContact">{{t('Contact support','联系支持')}}：{{policyContact}}</a></p><nav class="policy-links" :aria-label="t('Related policies','相关政策')"><RouterLink to="/terms">{{t('Terms of service','服务条款')}}</RouterLink><RouterLink to="/privacy">{{t('Privacy policy','隐私政策')}}</RouterLink><RouterLink to="/refunds">{{t('Refund policy','退款政策')}}</RouterLink><a href="https://www.creem.io/buyer-terms" rel="noopener noreferrer">{{t('Creem Buyer Terms','Creem 买家条款')}}</a><a href="https://www.creem.io/privacy" rel="noopener noreferrer">{{t('Creem privacy policy','Creem 隐私政策')}}</a><a href="https://policies.google.com/privacy" rel="noopener noreferrer">{{t('Google privacy policy','Google 隐私政策')}}</a></nav></main></template>
