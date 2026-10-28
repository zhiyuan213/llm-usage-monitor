# 上报示例与顺序图修正验证

2026-10-28，本地验证记录；推送前记录，不表示远端 CI 或部署已完成。

问题：生产协议要求 usage 出现时同时提供输入、输出、缓存三项（允许 null），但场景生成器、Node/Python/Java REST 示例和教程捕获测试均省略缓存项。修正生成源为 `cachedInputTokens: 0`，同步 REST/SDK 场景、可运行教程、两份 SDK ID 指南。增加真实 `Protocol.validateEvents` 校验，由同一生成源输出测试 fixture，防止仅比较错误示例而误判通过。

多模型示意改成三次明确的上报步骤，只有最后一批显示 Observation 3 + Trace；其他对象树明确标注非上报顺序。说明 Trace 最后上报时 startedAt 仍是整次请求开始时间，以及 Trace 到达前列表可能没有完整请求。

检查结果：

- `mvn -B -f backend/pom.xml verify`：204项通过、0失败/错误/跳过，包含2项新增真实协议回归测试；未修改生产后端协议或接口。Docker Maven 3.9 / Temurin 17 执行，日志 `.local/docs-cached-verify.log`。
- `npm test`：20个文件59项通过。`npm run build`：成功，生成11个公开页面。
- `node scripts/check-scenario-examples.mjs`：Node、Python、Java各4个场景，共12个实际执行通过；捕获的模型调用都包含缓存字段。
- 在 frontend 运行 `node scripts/check-tutorials.mjs`：Node、Python、Java、REST四份公开教程均实际返回 HTTP 200 / pending，捕获 usage 为完整三项。
- `node --test sdk/node/test.mjs scripts/sync-doc-examples.test.mjs`：10项通过。
- Node 24容器运行 `node scripts/npm-audit.mjs`：0漏洞；REST示例、场景及后端 fixture、SDK ID指南三组生成同步检查均通过。`git diff --check` 通过。
- Windows本机Java HTTP客户端首次遇到Unix域临时路径问题，设置 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:/devtools/tmp` 后四个Java场景及教程全部通过；没有修改产品代码来绕过问题。
- 文档浏览器回归：三次上报顺序与三份 JSON 一致，三个 usage 均含 cachedInputTokens=0。1920×1080及390×844检查通过，窄屏页面375px，无页面横向溢出。截图 `.local/docs-upload-order-desktop.png`。
