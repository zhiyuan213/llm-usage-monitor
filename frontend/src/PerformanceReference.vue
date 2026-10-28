<script setup lang="ts">
import { t } from "./lib";
defineProps<{ compact?: boolean }>();
</script>

<template>
  <section class="performance-reference" :aria-label="t('Performance reference', '性能参考')">
    <span class="eyebrow">{{ t('LOCAL TEST REFERENCE', '本地测试参考') }}</span>
    <h2>{{ t('Performance, with context', '性能参考与适用范围') }}</h2>
    <div class="performance-metrics">
      <article><strong>300</strong><span>{{ t('ingestion requests / second', '次上报请求 / 秒') }}</span></article>
      <article><strong>1</strong><span>{{ t('event / request in this test', '条事件 / 请求（本次测试）') }}</span></article>
      <article><strong>60 s</strong><span>{{ t('short test duration', '短时测试时长') }}</span></article>
    </div>
    <p>{{ t('A conservative reference from a local test on October 24, 2026, with a warmed-up service and synthetic data. This is not a production throughput guarantee, concurrency limit, or SLA.', '基于 2026 年 10 月 24 日本地预热后的合成数据测试，采用保守参考值。不代表线上吞吐保证、并发上限或 SLA。') }}</p>
    <RouterLink v-if="compact" to="/docs#performance">{{ t('Test conditions and integration guidance →', '查看测试条件与接入建议 →') }}</RouterLink>
    <template v-else>
      <h3>{{ t('What was measured', '测试条件与结果') }}</h3>
      <p>{{ t('One workspace and project, one new Trace event per request, 300 requests/s for 60 seconds, alongside 20 trace-list queries/s. The run sent 18,000 ingestion requests with no HTTP failures or dropped scheduled iterations; accepted data reconciled. This is a short test, not a long-duration capacity certification.', '单个工作空间、单个项目，每个请求包含 1 条新 Trace，持续 60 秒上报 300 次/秒，同时执行 20 次/秒调用列表查询。共发送 18,000 次上报，未出现 HTTP 失败或计划请求丢次，接收数据对账一致。这是短时测试，不是长期容量认证。') }}</p>
      <p>{{ t('Local containers: API 4 CPU / 6 GiB, Worker 4 CPU / 6 GiB, PostgreSQL 4 CPU / 8 GiB; load generator on the same host. Internet latency, event size, historical data, and deployment resources change results. The latest changes have not repeated this capacity test; production capacity remains unconfirmed.', '本地容器资源：API 4 CPU / 6 GiB、Worker 4 CPU / 6 GiB、PostgreSQL 4 CPU / 8 GiB，压测端位于同一主机。公网延迟、事件大小、历史数据量及部署资源都会影响结果。最新改动尚未重复此项容量测试，正式环境容量仍待确认。') }}</p>
      <h3>{{ t('Requests, events, and concurrent users', '请求量、事件量与并发用户') }}</h3>
      <p>{{ t('Requests/s measures HTTP batches; events/s measures events inside those batches. Neither measures concurrent users. The 300 requests/s figure is not a per-account allowance or an enforced API limit. Your plan quota and actual rate-limit responses still apply.', '请求/秒是 HTTP 批次数，事件/秒是批次内事件数，两者都不等于并发用户数。300 次/秒不是每个账号的配额，也不是接口强制限流值；实际使用仍受套餐额度及接口限流约束。') }}</p>
      <h3>{{ t('Integrate with backpressure', '接入与流量控制') }}</h3>
      <p>{{ t('HTTP 202 with status pending acknowledges committed acceptance, not completed processing. Use the batch-status API when completion matters. Start with low concurrency, batch events where appropriate, and increase gradually while monitoring latency and errors.', 'HTTP 202 且 status 为 pending 表示接收事务已提交，不代表处理完成。需要确认完成时请调用批次状态接口。从低并发开始，按需合并事件，观察延迟与错误后逐步增加流量。') }}</p>
      <p>{{ t('For 429, respect Retry-After; for retryable 5xx or network failures, use exponential backoff with jitter and preserve event identity and content. A timeout can have an uncertain outcome. Do not generate new event IDs for retries. Pause on exhausted quota or invalid credentials.', '遇到 429 遵循 Retry-After；可重试的 5xx 或网络失败采用指数退避并加抖动，保留事件身份与内容。超时可能意味着结果不确定，重试不要生成新事件 ID；额度耗尽或密钥无效时暂停上报。') }}</p>
      <RouterLink to="/docs#batch-status">{{ t('Query batch status →', '查询批次状态 →') }}</RouterLink>
    </template>
  </section>
</template>
