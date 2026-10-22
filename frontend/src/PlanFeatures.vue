<script setup lang="ts">
import { Check } from "lucide-vue-next";
import { t } from "./lib";
defineProps<{ index: number }>();
</script>
<template>
  <p>{{ t("Everything you need to understand usage.", "掌握应用调用与用量。") }}</p>
  <ul>
    <li><Check :size="16" />{{ index === 0 ? "50,000" : "100,000" }} units / {{ t("month", "月") }}</li>
    <li><Check :size="16" />{{ [t("30 days", "30 天"), t("90 days", "90 天"), t("3 years", "3 年")][index] }} {{ t("data access", "数据访问") }}</li>
    <li><Check :size="16" />Trace · Observation · Score</li>
    <li><Check :size="16" />{{ t("Stop at quota by default", "默认额度用尽后停止接收") }}</li>
  </ul>
</template>
