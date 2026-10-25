<script setup lang="ts">
import { Check } from "lucide-vue-next";
import { t } from "./lib";
defineProps<{ index: number }>();
</script>
<template>
  <p>
    {{ t("Everything you need to understand usage.", "掌握应用调用与用量。") }}
  </p>
  <ul>
    <li>
      <Check :size="16" />{{ index === 0 ? "50,000" : "100,000" }} units /
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
    <li v-if="index > 0"><Check :size="16" />{{ t('Cost diagnosis, release comparison and weekly reports', '费用诊断、发布对比与自动周报') }}</li>
    <li v-if="index > 0"><Check :size="16" />{{ t('Budget reminders and failure / slow-call analysis', '预算提醒与失败、慢调用分析') }}</li>
    <li v-if="index === 2"><Check :size="16" />{{ t('Custom budget warning and slow-call thresholds', '自定义预算提醒线与慢调用阈值') }}</li>
    <li>
      <Check :size="16" />{{
        t("Stop at quota by default", "默认额度用尽后停止接收")
      }}
    </li>
  </ul>
  <p v-if="index > 0" class="muted">{{ t("Retention applies while subscribed. After expiry, ingestion stops and you have 30 days to renew. Then Free retention applies and data older than 30 days is deleted. Deleted data cannot be recovered by renewing.", "订阅有效期间按上述期限保留数据。到期后停止新增接入，并提供30天续费宽限期；期满转为 Free，删除超过30天的调用数据。续费无法恢复已删除数据。") }}</p>
</template>
