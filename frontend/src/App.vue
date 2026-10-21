<script setup lang="ts">
import { language, setLanguage } from "./lib";
</script>
<template>
  <RouterView />
  <div class="language-floating">
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
