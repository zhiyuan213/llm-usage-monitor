<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { t } from './lib';
import { tutorials, environmentExample, receipt, queryExample } from './tutorial-content';
import { trackAction } from './analytics';
const route = useRoute();
const tutorial = computed(() => tutorials.find(item => item.slug === route.params.tutorial));
const copied = ref('');
async function copy(code: string, name: string) {
  try { await navigator.clipboard.writeText(code); copied.value = name; trackAction('copy_example', route.path); }
  catch { copied.value = 'failed'; }
}
</script>
<template>
  <main class="public-content learning-page">
    <template v-if="!route.params.tutorial">
      <div class="page-intro"><span class="eyebrow">{{ t('FROM CODE TO YOUR FIRST TRACE', '从代码到第一条调用记录') }}</span><h1>{{ t('Integration tutorials', '接入教程') }}</h1><p>{{ t('Start with one request. Each guide includes runnable code, the expected response and troubleshooting steps.', '从一条请求开始。每篇教程都包含可运行代码、预期结果和常见问题处理。') }}</p></div>
      <div class="tutorial-grid"><article v-for="item in tutorials" :key="item.slug" class="panel"><span class="badge">{{ item.slug === 'rest' ? 'REST / cURL' : item.slug === 'python' ? 'Python' : 'Node.js' }}</span><h2><RouterLink :to="`/tutorials/${item.slug}`" @click="trackAction('open_tutorial', '/tutorials')">{{ t(item.en, item.zh) }}</RouterLink></h2><p>{{ t(item.summary[0], item.summary[1]) }}</p><RouterLink :to="`/tutorials/${item.slug}`">{{ t('Start the tutorial →', '开始接入 →') }}</RouterLink></article></div>
      <p><RouterLink to="/demo">{{ t('Explore the demo before integrating →', '先看看演示效果 →') }}</RouterLink></p>
    </template>
    <article v-else-if="tutorial" class="tutorial-article">
      <RouterLink to="/tutorials">{{ t('← All tutorials', '← 全部教程') }}</RouterLink>
      <h1>{{ t(tutorial.en, tutorial.zh) }}</h1><p class="tutorial-lead">{{ t(tutorial.summary[0], tutorial.summary[1]) }}</p>
      <h2>{{ t('Before you start', '开始前准备') }}</h2><p>{{ t(tutorial.requirement[0], tutorial.requirement[1]) }}</p><p>{{ t('Examples use REST from your backend. Keep project API keys out of browser code, screenshots and source control. No model API key is required for this test.', '示例从服务端通过 REST 上报。不要将项目 API 密钥放入浏览器代码、截图或代码仓库。本次验证不需要模型供应商密钥。') }}</p>
      <h2>{{ t('1. Create a project and API key', '1. 创建项目与密钥') }}</h2><p>{{ t('Create a free account, verify your email, then create or select a project in Project management. Open API keys for that project, create a key and complete email verification. Save the key when it is displayed.', '免费注册并验证邮箱后，在项目管理中创建或选择项目。进入该项目的密钥管理，创建密钥并完成邮箱验证，在密钥展示时妥善保存。') }}</p><RouterLink to="/register">{{ t('Create a free account →', '免费创建账号 →') }}</RouterLink>
      <h2>{{ t('2. Set your environment', '2. 设置环境变量') }}</h2><p>{{ t('Choose the commands for your shell. The masked PowerShell prompt requires PowerShell 7.1+. With Windows Python, use python instead of python3 if needed.', '按所用终端选择命令。PowerShell 隐藏输入需要 7.1 或更新版本；Windows 的 Python 命令如为 python，请替换下方的 python3。') }}</p><div class="code-box"><div>{{ t('Environment', '环境变量') }}<button @click="copy(environmentExample, 'environment')">{{ copied === 'environment' ? t('Copied', '已复制') : t('Copy', '复制') }}</button></div><pre>{{ environmentExample }}</pre></div>
      <h2>{{ t('3. Send a sample trace', '3. 发送示例调用记录') }}</h2><p>{{ t('Save the following code as', '将以下代码保存为') }} <code>{{ tutorial.filename }}</code>{{ t('. Run it from the same terminal where you set the variables.', '。在设置好环境变量的同一个终端中执行。') }}</p>
      <div class="code-box"><div>{{ tutorial.filename }}<button @click="copy(tutorial.code, 'source')">{{ copied === 'source' ? t('Copied', '已复制') : t('Copy', '复制') }}</button></div><pre>{{ tutorial.code }}</pre></div><div class="code-box"><div>{{ t('Run', '执行') }}</div><pre>{{ tutorial.command }}</pre></div><p>{{ t(tutorial.usage[0], tutorial.usage[1]) }}</p>
      <h2>{{ t('4. Check the result', '4. 检查接收结果') }}</h2><p>{{ t('A successful upload returns HTTP 200 with a receipt like this. pending means received for asynchronous processing; it does not mean the record is already queryable.', '上报成功返回 HTTP 200，回执如下。pending 表示已接收并等待异步处理，不表示记录已经可以查询。') }}</p><div class="code-box"><div>HTTP 200</div><pre>{{ receipt }}</pre></div><p>{{ t('For live processing status, replace <batch-id> with the returned batchId and use the same project key. This query is optional.', '如需查询实时处理状态，将 <batch-id> 替换为回执中的 batchId，使用同一项目密钥。此查询为可选步骤。') }}</p><div class="code-box"><div>{{ t('Optional status query (POSIX shell)', '可选状态查询（POSIX shell）') }}</div><pre>{{ queryExample }}</pre></div>
      <h2>{{ t('5. Find the trace in your console', '5. 在控制台查看记录') }}</h2><p>{{ t('Open Project management, select the project associated with your key, and open Traces. Refresh after processing and look for Example request. This minimal example has no model usage or price, so model cost may show Unknown; this is expected.', '打开项目管理，选择密钥对应的项目，进入调用记录。处理完成后刷新，找到 Example request。这个最小示例未上报模型用量或价格，模型成本可能显示“未知”，属于正常情况。') }}</p><p><RouterLink to="/docs#fields">{{ t('Add model usage with the full field reference →', '查看完整字段，继续添加模型用量 →') }}</RouterLink></p>
      <h2>{{ t('Troubleshooting', '常见问题') }}</h2><ul><li>{{ t('401: check the key, project and whether the key has been revoked.', '401：检查密钥是否正确、项目是否匹配，以及密钥是否已被撤销。') }}</li><li>{{ t('400: check field names, timestamps and required values against the field reference.', '400：按字段文档检查字段名称、时间格式及必填值。') }}</li><li>{{ t('Quota or subscription errors: check Billing before retrying. Retrying does not create more quota.', '额度或订阅错误：先检查套餐与账单，重试不会增加额度。') }}</li><li>{{ t('Network timeout: the server may already have accepted the request. Retry with the same event IDs and content; a new run generates new objects.', '网络超时：服务器可能已接收请求。请保留相同事件 ID 和内容重试，重新运行示例会生成新对象。') }}</li></ul>
      <p v-if="copied === 'failed'" role="status">{{ t('Copy was unavailable. Select and copy the code manually.', '暂时无法自动复制，请选中代码手动复制。') }}</p><nav class="tutorial-related"><RouterLink v-for="item in tutorials.filter(item => item.slug !== tutorial?.slug)" :key="item.slug" :to="`/tutorials/${item.slug}`">{{ t(item.en, item.zh) }} →</RouterLink></nav>
    </article>
    <template v-else><h1>{{ t('Tutorial not found', '未找到教程') }}</h1><RouterLink to="/tutorials">{{ t('View all tutorials', '查看全部教程') }}</RouterLink></template>
  </main>
</template>
