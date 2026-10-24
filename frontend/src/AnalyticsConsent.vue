<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { consent, setConsent, publicPage, trackPage } from './analytics';
import { t } from './lib';
const route = useRoute();
const choice = ref(consent()), editing = ref(false);
function choose(accepted: boolean) {
  setConsent(accepted); choice.value = accepted ? 'granted' : 'denied'; editing.value = false;
  if (accepted) trackPage(route.path);
}
watch(() => route.path, () => { editing.value = false; });
</script>
<template>
  <template v-if="publicPage(route.path)">
    <section v-if="!choice || editing" class="analytics-consent" aria-label="Website statistics / 网站统计">
      <p>{{ t('Allow Google Analytics to measure visits to public pages and help us improve the website? It is optional. We do not send account details, form content or URL parameters.', '是否允许使用 Google Analytics 统计公开页面的访问，帮助我们改进网站？你可以拒绝；我们不会发送账号资料、表单内容或网址参数。') }} <RouterLink to="/privacy">{{ t('Privacy details', '隐私说明') }}</RouterLink></p>
      <div><button class="btn" @click="choose(false)">{{ t('Decline', '拒绝') }}</button><button class="btn primary" @click="choose(true)">{{ t('Allow statistics', '允许统计') }}</button></div>
    </section>
    <button v-else class="analytics-settings" @click="editing = true">{{ t('Statistics preferences', '统计偏好') }}</button>
  </template>
</template>
