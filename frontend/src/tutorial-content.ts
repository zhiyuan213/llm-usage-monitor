import { examples } from './examples';
export const tutorials = [
  {
    slug: 'nodejs', en: 'Track your first AI request with Node.js', zh: '用 Node.js 记录第一条 AI 请求',
    summary: ['Send a trace from your Node.js backend, check the receipt, and find it in the console. No extra package required.', '从 Node.js 后端发送调用记录、检查接收结果，并在控制台中查看。无需安装额外依赖。'],
    requirement: ['Node.js 20 or later, a MeterTrail account and a project API key.', '需要 Node.js 20 或更新版本、MeterTrail 账号和一把项目 API 密钥。'],
    filename: 'send.mjs', command: 'node send.mjs', code: examples['Node.js'],
    usage: ['Run this code on your server after the AI request finishes. Replace the sample name, status and duration with your measured values. This example reports metadata only; it does not call a model or include prompts.', '在 AI 请求结束后，由你的服务端执行上报。将示例名称、状态和耗时换成实际测量值。此示例仅发送元数据，不调用模型，也不包含提示词。'],
  },
  {
    slug: 'python', en: 'Track your first AI request with Python', zh: '用 Python 记录第一条 AI 请求',
    summary: ['Use the Python standard library to send a trace and verify your first successful integration.', '使用 Python 标准库发送调用记录，完成第一次接入验证。'],
    requirement: ['Python 3.10 or later, a MeterTrail account and a project API key.', '需要 Python 3.10 或更新版本、MeterTrail 账号和一把项目 API 密钥。'],
    filename: 'send.py', command: 'python3 send.py', code: examples.Python,
    usage: ['Use this server-side example after your application finishes a request. Read duration from your timer and status from the actual result. The example uses no third-party packages and does not send prompt or response text.', '在应用完成请求后使用此服务端示例。耗时来自实际计时，状态来自真实执行结果。示例不依赖第三方包，不发送提示词或回答正文。'],
  },
  {
    slug: 'java', en: 'Track your first AI request with Java', zh: '用 Java 记录第一条 AI 请求',
    summary: ['Use the Java HTTP client to send a request and model usage, check the receipt and find it in the console.', '使用 Java HTTP 客户端上报请求和模型用量，检查接收结果，并在控制台查看。'],
    requirement: ['JDK 17 or later, a MeterTrail account and a project API key. No Maven dependencies required.', '需要 JDK 17 或更新版本、MeterTrail 账号和项目 API 密钥。无需额外 Maven 依赖。'],
    filename: 'Send.java', command: 'java Send.java', code: examples.Java,
    usage: ['Run on your backend after the AI request finishes. Each run creates a trace and a model-call observation (2 units). Reuse the unchanged payload and IDs for retries. This example does not call a model or send prompt content.', '在 AI 请求结束后由服务端上报。每次运行创建一条请求和一条模型调用，共 2 units。重试时保留原始数据和 ID。示例不调用模型，不发送提示词正文。'],
  },
  {
    slug: 'rest', en: 'Integrate directly through the HTTP API', zh: '直接通过 HTTP API 接入',
    summary: ['No SDK required. Use Postman, cURL or any HTTP client; this tutorial demonstrates cURL.', '无需 SDK，可用 Postman、cURL 或任意语言的 HTTP 客户端调用，本教程以 cURL 演示。'],
    requirement: ['A POSIX shell (macOS, Linux or WSL), Python 3 and cURL, plus a project API key.', '需要 POSIX shell（macOS、Linux 或 WSL）、Python 3、cURL 和项目 API 密钥。'],
    filename: 'send.sh', command: 'sh send.sh',
    code: `#!/usr/bin/env sh
set -eu
: "\${METERTRAIL_API_KEY:?Set your project API key}"
: "\${METERTRAIL_ENDPOINT:?Set endpoint}"
"\${PYTHON:-python3}" - <<'PY' > batch.json
${examples.Python.split('request = urllib.request.Request(')[0]}
print(json.dumps(batch))
PY
curl --fail-with-body --max-time 10 "$METERTRAIL_ENDPOINT/v1/events/batch" \\
  -H "Authorization: Bearer $METERTRAIL_API_KEY" \\
  -H 'Content-Type: application/json' --data @batch.json`,
    usage: ['Each run sends one request and one model-call observation (2 monitoring units). For a network retry, resend the same batch.json unchanged instead of generating new IDs. This is a sample trace, not a real model request.', '每次运行上报一条请求及一条模型调用，共 2 个监控 units。网络重试时原样重发同一份 batch.json，不要重新生成 ID。这是一条示例调用记录，不会请求模型。'],
  },
];
export const environmentExample = `# macOS / Linux / WSL
export METERTRAIL_ENDPOINT="https://metertrail.com"
printf 'Project API key (input is visible): '
IFS= read -r METERTRAIL_API_KEY
export METERTRAIL_API_KEY

# Windows PowerShell (Node.js / Python / Java tutorials)
$env:METERTRAIL_ENDPOINT = "https://metertrail.com"
$env:METERTRAIL_API_KEY = Read-Host "Project API key" -MaskInput`;
export const receipt = '{"batchId":"<batch-id>","received":2,"status":"pending"}';
export const queryExample = `curl --fail-with-body --max-time 10 "https://metertrail.com/v1/batches/<batch-id>" \\
  -H "Authorization: Bearer $METERTRAIL_API_KEY"`;
