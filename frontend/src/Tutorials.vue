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
      <div class="tutorial-grid integration-tutorial-grid"><article v-for="item in tutorials" :key="item.slug" class="panel"><span class="badge">{{ item.slug === 'rest' ? 'HTTP API' : item.slug === 'python' ? 'Python' : item.slug === 'java' ? 'Java' : 'Node.js' }}</span><h2><RouterLink :to="`/tutorials/${item.slug}`" @click="trackAction('open_tutorial', '/tutorials')">{{ t(item.en, item.zh) }}</RouterLink></h2><p>{{ t(item.summary[0], item.summary[1]) }}</p><RouterLink :to="`/tutorials/${item.slug}`">{{ t('Start the tutorial →', '开始接入 →') }}</RouterLink></article></div>
      <p><RouterLink to="/demo">{{ t('Explore the demo before integrating →', '先看看演示效果 →') }}</RouterLink></p>
    </template>
    <article v-else-if="tutorial" class="tutorial-article">
      <RouterLink to="/tutorials">{{ t('← All tutorials', '← 全部教程') }}</RouterLink>
      <h1>{{ t(tutorial.en, tutorial.zh) }}</h1><p class="tutorial-lead">{{ t(tutorial.summary[0], tutorial.summary[1]) }}</p>
      <h2>{{ t('Before you start', '开始前准备') }}</h2><p>{{ t(tutorial.requirement[0], tutorial.requirement[1]) }}</p><p>{{ t('Examples use REST from your backend. Keep project API keys out of browser code, screenshots and source control. No model API key is required for this test.', '示例从服务端通过 REST 上报。不要将项目 API 密钥放入浏览器代码、截图或代码仓库。本次验证不需要模型供应商密钥。') }}</p>
      <h2>{{ t('1. Create a project and API key', '1. 创建项目与密钥') }}</h2><p>{{ t('Create a free account, verify your email, then create or select a project in Project management. Open API keys for that project, create a key. The first key in a project needs no additional email verification; later keys require it. Save the key when it is displayed.', '免费注册并验证邮箱后，在项目管理中创建或选择项目。进入该项目的密钥管理，创建密钥；项目首把密钥无需额外邮箱验证，后续创建仍需验证，在密钥展示时妥善保存。') }}</p><RouterLink to="/register">{{ t('Create a free account →', '免费创建账号 →') }}</RouterLink>
      <h2>{{ t('2. Set your environment', '2. 设置环境变量') }}</h2><p>{{ t('Choose the commands for your shell. The masked PowerShell prompt requires PowerShell 7.1+. With Windows Python, use python instead of python3 if needed.', '按所用终端选择命令。PowerShell 隐藏输入需要 7.1 或更新版本；Windows 的 Python 命令如为 python，请替换下方的 python3。') }}</p><div class="code-box"><div>{{ t('Environment', '环境变量') }}<button @click="copy(environmentExample, 'environment')">{{ copied === 'environment' ? t('Copied', '已复制') : t('Copy', '复制') }}</button></div><pre>{{ environmentExample }}</pre></div>
      <p>{{ t('This is the same one-call scenario: one Trace and one Observation share traceId and are sent together. This tutorial uses the HTTP client directly; SDK examples are available separately.', '这里采用相同的单次模型调用场景：一条 Trace 和一条 Observation 共用 traceId，一起发送。本教程直接使用 HTTP 客户端，SDK 示例另行提供。') }} <RouterLink to="/docs#protocol">{{t('All REST scenarios →','查看完整 REST 场景 →')}}</RouterLink> · <RouterLink to="/docs#sdk">{{t('SDK scenarios →','查看 SDK 场景 →')}}</RouterLink></p>
      <h2>{{ t('3. Send a sample trace', '3. 发送示例调用记录') }}</h2><p>{{ t('Save the following code as', '将以下代码保存为') }} <code>{{ tutorial.filename }}</code>{{ t('. Run it from the same terminal where you set the variables.', '。在设置好环境变量的同一个终端中执行。') }}</p>
      <template v-if="tutorial.additionalFiles">
        <p>{{t('Create the Maven project using the paths below. Independent DTO classes use private fields and getters/setters; MeterTrailClient handles serialization and HTTP. Run from the folder containing pom.xml.','按下方路径创建 Maven 项目。独立 DTO 类使用私有字段和 getter/setter，由 MeterTrailClient 封装序列化与 HTTP 调用。在 pom.xml 所在目录执行命令。')}}</p>
        <div v-for="file in tutorial.additionalFiles.filter(file=>file.filename==='pom.xml')" :key="file.filename" class="code-box"><div>{{file.filename}}<button @click="copy(file.code,file.filename)">{{copied===file.filename?t('Copied','已复制'):t('Copy','复制')}}</button></div><pre>{{file.code}}</pre></div>
      </template>
      <div class="code-box"><div>{{ tutorial.filename }}<button @click="copy(tutorial.code, 'source')">{{ copied === 'source' ? t('Copied', '已复制') : t('Copy', '复制') }}</button></div><pre>{{ tutorial.code }}</pre></div><div class="code-box"><div>{{ t('Run', '执行') }}</div><pre>{{ tutorial.command }}</pre></div><p>{{ t(tutorial.usage[0], tutorial.usage[1]) }}</p>
      <template v-if="tutorial.additionalFiles">
        <h3>{{t('Reusable client and request objects','可复用的客户端与请求对象')}}</h3>
        <p>{{t('Save each class at the path shown. All DTOs are regular JavaBeans with no Lombok requirement. The example above uses these same files.','将各个类保存到标注的路径。所有 DTO 都是普通 JavaBean，不依赖 Lombok；上面的调用示例直接使用这些文件。')}}</p>
        <div v-for="file in tutorial.additionalFiles.filter(file=>file.filename!=='pom.xml')" :key="file.filename" class="code-box"><div>{{file.filename}}<button @click="copy(file.code,file.filename)">{{copied===file.filename?t('Copied','已复制'):t('Copy','复制')}}</button></div><pre>{{file.code}}</pre></div>
      </template>
      <h2>{{ t('4. Check the result', '4. 检查接收结果') }}</h2><p>{{ t('A successful upload returns HTTP 202 with a receipt like this. pending means received for asynchronous processing; it does not mean the record is already queryable.', '上报成功返回 HTTP 202，回执如下。pending 表示已接收并等待异步处理，不表示记录已经可以查询。') }}</p><div class="code-box"><div>HTTP 202</div><pre>{{ receipt }}</pre></div><p>{{ t('For live processing status, replace <batch-id> with the returned batchId and use the same project key. This query is optional.', '如需查询实时处理状态，将 <batch-id> 替换为回执中的 batchId，使用同一项目密钥。此查询为可选步骤。') }}</p><div class="code-box"><div>{{ t('Optional status query (POSIX shell)', '可选状态查询（POSIX shell）') }}</div><pre>{{ queryExample }}</pre></div>
      <h2>{{ t('5. Find your request', '5. 查看上报结果') }}</h2>
      <p>{{ t('Open Calls for the same project and refresh after processing. You should see Document analysis, 2,100 ms, example-model, 1,900 Tokens, demo-user / demo-customer and success. Each run sends a Trace and a model-call Observation, consuming 2 units.', '在相同项目的调用记录中，处理完成后刷新：应显示 Document analysis、2,100 ms、example-model、1,900 Token、demo-user / demo-customer 和成功状态。每次上报一条请求和一条模型调用，共 2 units。') }}</p>
      <p>{{ t('These are synthetic values: replace provider, model, usage and identity fields with your actual measurements. In Project settings, add provider example / model example-model with USD per million Tokens: input 3, output 8, cached input 0.5. Configure the price before sending a new example: its estimated cost should be US$0.0077. Existing records are not automatically repriced. Do not use these demonstration rates as actual provider prices.', '以上为模拟值，实际接入时替换为真实供应商、模型、用量及用户标识。在项目设置中，先配置供应商 example、模型 example-model，每百万 Token 美元价格：输入 3、输出 8、缓存输入 0.5；再上报新示例，估算费用应为 US$0.0077。旧记录不会自动重算。这些只是演示价格，不代表模型供应商实际报价。') }}</p>
      <p><RouterLink to="/docs#fields">{{ t('Common field reference →', '查看常用字段映射 →') }}</RouterLink></p>
      <h2>{{ t('Troubleshooting', '常见问题') }}</h2><ul><li>{{ t('401: check the key, project and whether the key has been revoked.', '401：检查密钥是否正确、项目是否匹配，以及密钥是否已被撤销。') }}</li><li>{{ t('400: check field names, timestamps and required values against the field reference.', '400：按字段文档检查字段名称、时间格式及必填值。') }}</li><li>{{ t('Quota or subscription errors: check Billing before retrying. Retrying does not create more quota.', '额度或订阅错误：先检查套餐与账单，重试不会增加额度。') }}</li><li>{{ t('Network timeout: the server may already have accepted the request. Retry with the same event IDs and content; a new run generates new objects.', '网络超时：服务器可能已接收请求。请保留相同事件 ID 和内容重试，重新运行示例会生成新对象。') }}</li></ul>
      <p v-if="copied === 'failed'" role="status">{{ t('Copy was unavailable. Select and copy the code manually.', '暂时无法自动复制，请选中代码手动复制。') }}</p><nav class="tutorial-related"><RouterLink v-for="item in tutorials.filter(item => item.slug !== tutorial?.slug)" :key="item.slug" :to="`/tutorials/${item.slug}`">{{ t(item.en, item.zh) }} →</RouterLink></nav>
    </article>
    <template v-else><h1>{{ t('Tutorial not found', '未找到教程') }}</h1><RouterLink to="/tutorials">{{ t('View all tutorials', '查看全部教程') }}</RouterLink></template>
  </main>
</template>
