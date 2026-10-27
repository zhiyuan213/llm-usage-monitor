<script setup lang="ts">
import { ref, watch, nextTick } from 'vue';
import { useRoute } from 'vue-router';
import { consent, setConsent, publicPage, trackPage } from './analytics';
import { t } from './lib';
const route = useRoute();
const choice = ref(consent()), editing = ref(false);
const panel=ref<HTMLElement|null>(null);
async function openSettings(){editing.value=true;await nextTick();panel.value?.scrollIntoView({block:'center',behavior:'smooth'});panel.value?.focus({preventScroll:true});}
function choose(accepted: boolean) {
  setConsent(accepted); choice.value = accepted ? 'granted' : 'denied'; editing.value = false;
  if (accepted) trackPage(route.path);
}
watch(() => route.path, () => { editing.value = false; });
</script>
<template>
  <template v-if="publicPage(route.path)">
    <section ref="panel" v-if="!choice || editing" tabindex="-1" class="analytics-consent" aria-label="Website statistics / 网站统计">
      <p>{{ t('Optional Google Analytics helps improve our public website.', '可选的 Google Analytics 统计用于改进公开网站。') }} <RouterLink to="/privacy">{{ t('Privacy details', '隐私说明') }}</RouterLink></p>
      <div><button class="btn" @click="choose(false)">{{ t('Decline', '拒绝') }}</button><button class="btn primary" @click="choose(true)">{{ t('Allow statistics', '允许统计') }}</button></div>
      <button type="button" class="consent-close" :aria-label="t('Close without allowing statistics','关闭且不允许统计')" @click="choose(false)">×</button>
    </section>
    <button class="analytics-settings" @click="openSettings">{{ t('Statistics preferences', '统计偏好') }}</button>
  </template>
</template>
