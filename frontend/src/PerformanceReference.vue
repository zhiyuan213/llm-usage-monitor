<script setup lang="ts">
import { t } from "./lib";
defineProps<{ compact?: boolean }>();
</script>

<template>
  <section class="performance-reference" :aria-label="t('Performance reference', '性能参考')">
    <span class="eyebrow">{{ t('LOCAL TEST REFERENCE', '本地测试参考') }}</span>
    <h2>{{ t('Performance, with context', '性能参考与适用范围') }}</h2>
    <div class="performance-metrics">
      <article><strong>500</strong><span>{{ t('target events / second', '目标事件数 / 秒') }}</span></article>
      <article><strong>1</strong><span>{{ t('event / request in this test', '条事件 / 请求（本次测试）') }}</span></article>
      <article><strong>60 s</strong><span>{{ t('short test duration', '短时测试时长') }}</span></article>
    </div>
    <p>{{ t('A target reference tested locally on October 28, 2026, with a warmed-up service and synthetic data. This is not a production throughput guarantee, concurrency limit, or SLA.', '基于 2026 年 10 月 28 日本地预热后的合成数据测试，展示目标参考值。不代表线上吞吐保证、并发上限或 SLA。') }}</p>
    <RouterLink v-if="compact" to="/docs#performance">{{ t('Test conditions and integration guidance →', '查看测试条件与接入建议 →') }}</RouterLink>
    <template v-else>
      <h3>{{ t('What was measured', '测试条件与结果') }}</h3>
      <p>{{ t('One workspace and project, one event per request, mixing Trace and generation Observation events. At a target of 500 events/s for 60 seconds, the warmed-up run sent 29,876 requests, all accepted with HTTP 202; p95 latency was 34.55 ms. The generator dropped 124 scheduled iterations, so the full 500 EPS target was not met. Accepted events reconciled after processing.', '单个工作空间、单个项目，每个请求 1 条事件，混合 Trace 和模型生成 Observation。按 500 EPS 目标持续 60 秒，预热后实际发送 29,876 次请求，全部返回 HTTP 202，P95 延迟 34.55 ms。压测端有 124 次计划请求未发出，因此尚未完整达到 500 EPS 目标；已接收数据处理后对账一致。') }}</p>
      <p>{{ t('Local containers: API and Worker each 1.5 CPU / 768 MiB, PostgreSQL 2 CPU / 1 GiB, Redis 1 CPU / 512 MiB with AOF always enabled. The generator shares the host. This short test is not a long-duration capacity certification; Internet latency, event size, historical data and deployment resources change results.', '本地容器资源：API、Worker 各 1.5 CPU / 768 MiB，PostgreSQL 2 CPU / 1 GiB，Redis 1 CPU / 512 MiB，启用 AOF always；压测端位于同一主机。这是短时测试，不是长期容量认证；公网延迟、事件大小、历史数据量及部署资源都会影响结果。') }}</p>
      <h3>{{ t('Requests, events, and concurrent users', '请求量、事件量与并发用户') }}</h3>
      <p>{{ t('Requests/s measures HTTP batches; events/s measures events inside those batches. Neither measures concurrent users. The 500 events/s target is not a per-account allowance or an enforced API limit. Your plan quota and actual rate-limit responses still apply.', '请求/秒是 HTTP 批次数，事件/秒是批次内事件数，两者都不等于并发用户数。500 EPS 目标不是每个账号的配额，也不是接口强制限流值；实际使用仍受套餐额度及接口限流约束。') }}</p>
      <h3>{{ t('Integrate with backpressure', '接入与流量控制') }}</h3>
      <p>{{ t('HTTP 202 with status pending acknowledges Redis reception and buffering, not completed processing. Use the batch-status API when completion matters. Start with low concurrency, batch events where appropriate, and increase gradually while monitoring latency and errors.', 'HTTP 202 且 status 为 pending 表示 Redis 已接收并缓冲，不代表处理完成。需要确认完成时请调用批次状态接口。从低并发开始，按需合并事件，观察延迟与错误后逐步增加流量。') }}</p>
      <p>{{ t('For 429, respect Retry-After; for retryable 5xx or network failures, use exponential backoff with jitter and preserve event identity and content. A timeout can have an uncertain outcome. Do not generate new event IDs for retries. Pause on exhausted quota or invalid credentials.', '遇到 429 遵循 Retry-After；可重试的 5xx 或网络失败采用指数退避并加抖动，保留事件身份与内容。超时可能意味着结果不确定，重试不要生成新事件 ID；额度耗尽或密钥无效时暂停上报。') }}</p>
      <RouterLink to="/docs#batch-status">{{ t('Query batch status →', '查询批次状态 →') }}</RouterLink>
    </template>
  </section>
</template>
