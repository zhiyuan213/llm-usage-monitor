<script setup lang="ts">
import { t, n, language } from "./lib";
defineProps<{ page: number; total: number; loading: boolean; exportUrl?: string }>();
defineEmits<{ change: [page: number] }>();
</script>
<template>
  <div class="query-controls">
    <div v-if="exportUrl" class="query-export">
      <a class="btn" :href="exportUrl + '&format=xlsx&lang=' + language">{{ t("Export Excel", "导出 Excel") }}</a>
      <a class="btn" :href="exportUrl + '&format=pdf&lang=' + language">{{ t("Export PDF", "导出 PDF") }}</a>
    </div>
    <div class="query-pages">
      <span>{{ t("Total", "共") }} {{ n(total) }} {{ t("rows · 5 per page", "条 · 每页 5 条") }} · {{ page + 1 }} / {{ Math.max(1, Math.ceil(total / 5)) }}</span>
      <button class="btn" :disabled="loading || page === 0" @click="$emit('change', page - 1)">{{ t("Previous", "上一页") }}</button>
      <button class="btn" :disabled="loading || (page + 1) * 5 >= total" @click="$emit('change', page + 1)">{{ t("Next", "下一页") }}</button>
    </div>
  </div>
</template>
