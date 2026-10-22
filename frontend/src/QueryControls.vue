<script setup lang="ts">
import { t, n, language } from "./lib";
defineProps<{ page: number; size: number; total: number; loading: boolean; exportUrl?: string }>();
defineEmits<{ change: [page: number]; resize: [size: number] }>();
</script>
<template>
  <div class="query-controls">
    <div v-if="exportUrl" class="query-export">
      <a class="btn" :href="exportUrl + '&format=xlsx&lang=' + language">{{ t("Export Excel", "导出 Excel") }}</a>
      <a class="btn" :href="exportUrl + '&format=pdf&lang=' + language">{{ t("Export PDF", "导出 PDF") }}</a>
    </div>
    <div class="query-pages">
      <label class="page-size">{{ t("Rows per page", "每页条数") }}
        <select :value="size" :disabled="loading" @change="$emit('resize', Number(($event.target as HTMLSelectElement).value))">
          <option v-for="option in [5, 10, 50, 100]" :key="option" :value="option">{{ option }}</option>
        </select>
      </label>
      <span>{{ t("Total", "共") }} {{ n(total) }} {{ t("rows", "条") }} · {{ page + 1 }} / {{ Math.max(1, Math.ceil(total / size)) }}</span>
      <button class="btn" :disabled="loading || page === 0" @click="$emit('change', page - 1)">{{ t("Previous", "上一页") }}</button>
      <button class="btn" :disabled="loading || (page + 1) * size >= total" @click="$emit('change', page + 1)">{{ t("Next", "下一页") }}</button>
    </div>
  </div>
</template>
