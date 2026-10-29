<script setup lang="ts">
import {t} from './lib';
</script>
<template>
  <section id="data-handling" class="data-handling-summary">
    <h2>{{t('Data processing and retention','数据处理与保留说明')}}</h2>
    <p>{{t('MeterTrail receives metadata asynchronously: IDs, timestamps, status, latency, provider/model names, token usage and optional scores or customer labels. It does not require prompt or answer content and does not proxy model requests. Use pseudonymous customer IDs; do not report secrets.','MeterTrail 异步接收标识、时间、状态、耗时、供应商与模型、Token 用量，以及可选评分和客户标签。不要求提示词或回答正文，也不代理模型请求。客户 ID 建议使用内部化名标识，不要上报秘密。')}}</p>
    <h3>{{t('Different records have different retention rules','不同记录的保留规则')}}</h3>
    <ul>
      <li>{{t('Analytical detail: Free 30 days, Core 90 days, Pro 3 years, on a rolling basis. Previous paid retention has a 30-day grace after paid access ends; Free retention then applies.','分析明细：Free 滚动30天、Core 90天、Pro 3年；付费权益结束后原保留设置有30天宽限，之后适用 Free 保留规则。')}}</li>
      <li>{{t('Audit records: recorded account and workspace operations, separate from Trace/Observation retention. There is currently no fixed automatic expiry. Project or account deletion retains audit records; this is not a promise of permanent storage or a guaranteed audit retention period. Export records you need; contact support for access or deletion requests.','审计记录：已记录的账号与工作空间操作，独立于 Trace/Observation 保留窗口。目前没有固定自动到期清理期限；删除项目或账号后仍保留审计记录。这不代表永久保存或保证某个审计年限。请导出需要的记录，访问或删除请求可联系支持。')}}</li>
      <li>{{t('Project deletion revokes keys immediately and removes analytical detail asynchronously. Necessary payment and security records are retained separately. Backup copies may outlive the queryable detail; no fixed backup expiry is currently promised.','删除项目立即撤销密钥，分析明细异步删除；必要支付与安全记录另行保留。备份副本可能晚于可查询明细删除，目前不承诺固定备份到期时间。')}}</li>
    </ul>
    <h3>{{t('Service providers and boundaries','服务提供方与能力边界')}}</h3>
    <p>{{t('Tencent Cloud provides infrastructure and transactional email. Creem processes Creem payments; legacy Paddle payment records may remain. GitHub is used only for optional sign-in; Google Analytics only for consented public-site statistics. Providers may process data outside your location.','腾讯云提供基础设施和事务邮件；Creem 处理相应支付，历史 Paddle 支付记录可能保留。GitHub 用于可选登录；Google Analytics 仅用于经同意的公开网站统计。提供方可能在你所在地以外处理数据。')}}</p>
    <p>{{t('This describes current implementation, not a signed DPA, a complete contractual subprocessor register, a data-residency guarantee, GDPR/HIPAA certification, SSO, SIEM integration or invoice reconciliation. Those services are not included in a plan unless explicitly provided.','本说明描述当前实现，不是已签署 DPA、完整合同子处理方清单、数据驻留保证或 GDPR/HIPAA 认证；也不代表提供 SSO、SIEM 接入或供应商账单对账。未明确提供的能力不包含在套餐中。')}}</p>
    <RouterLink to="/privacy">{{t('Privacy policy and contact details','隐私政策与联系方式')}}</RouterLink>
  </section>
</template>
