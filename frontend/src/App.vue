<script setup lang="ts">
import { language, setLanguage } from "./lib";
import AnalyticsConsent from './AnalyticsConsent.vue';
import ConsoleToast from './ConsoleToast.vue';
</script>
<template>
  <RouterView />
  <ConsoleToast v-if="$route.path.startsWith('/app')" />
  <AnalyticsConsent />
  <div
    class="language-floating"
    :class="{
      'console-language': $route.path.startsWith('/app'),
      'public-language': !$route.path.startsWith('/app'),
    }"
  >
    <select
      aria-label="Language / 语言"
      :value="language"
      @change="setLanguage(($event.target as HTMLSelectElement).value)"
    >
      <option value="en">English</option>
      <option value="zh-CN">简体中文</option>
    </select>
  </div>
</template>
