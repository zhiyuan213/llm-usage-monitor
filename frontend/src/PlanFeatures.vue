<script setup lang="ts">
import { Check } from "lucide-vue-next";
import { t } from "./lib";
defineProps<{ index: number; compactRetention?: boolean }>();
</script>
<template>
  <p>
    {{ [t("Start tracking your application.", "开始记录应用调用。"), t("Diagnose costs and follow recent releases.", "排查费用，跟踪近期发布表现。"), t("Keep a longer history with custom reminders.", "长期追溯历史，自定义提醒阈值。")][index] }}
  </p>
  <ul>
    <li>
      <Check :size="16" />{{ ["50,000", "100,000", "1,000,000"][index] }} units /
      {{ t("month", "月") }}
    </li>
    <li>
      <Check :size="16" />{{
        [t("30 days", "30 天"), t("90 days", "90 天"), t("3 years", "3 年")][
          index
        ]
      }}
      {{ t("of rolling data retention", "滚动数据保留") }}
    </li>
    <li><Check :size="16" />Trace · Observation · Score</li>
    <li><Check :size="16" />{{ t("Audit Excel/PDF export and customer attribution", "审计 Excel/PDF 导出与客户归因") }}</li>
    <li><Check :size="16" />{{ t("Project-specific model price versions", "项目自定义模型价格版本") }}</li>
    <li v-if="index > 0"><Check :size="16" />{{ t('Cost diagnosis, release comparison and weekly reports', '费用诊断、发布对比与自动周报') }}</li>
    <li v-if="index > 0"><Check :size="16" />{{ t('Budget reminders and failure / slow-call analysis', '预算提醒与失败、慢调用分析') }}</li>
    <li v-if="index === 2"><Check :size="16" />{{ t('Custom budget warning and slow-call thresholds', '自定义预算提醒线与慢调用阈值') }}</li>
    <li>
      <Check :size="16" />{{
        t("Stop at quota by default", "默认额度用尽后停止接收")
      }}
    </li>
  </ul>
  <p v-if="index > 0 && !compactRetention" class="muted">{{ t("Retention applies while subscribed. Cancellation returns the account to Free with Free quota. Previous paid data has a 30-day retention grace period; afterward Free retention applies. Expired unpaid subscriptions may pause ingestion. Deleted data cannot be recovered by renewing.", "订阅有效期间按上述期限保留数据。正式取消后恢复 Free，新增接入按 Free 额度执行；原付费数据保留30天宽限期，之后按 Free 规则保留。到期未续费可能暂停接入。续费无法恢复已删除数据。") }}</p>
</template>
