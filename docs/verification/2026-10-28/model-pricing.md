# 自动模型计价验证（2026-10-28）

本地验证记录；未推送、未检查远端 CI、未部署，不代表线上已启用。

## 最终检查

- `mvn -B -f backend/pom.xml verify`：通过，201项测试，0失败/错误/跳过。通过 Docker Maven 3.9 / Temurin 17 执行，挂载 Docker socket，Testcontainers 使用独立 PostgreSQL。完整输出：`.local/model-pricing-verify-final.log`。
- 新增 `ModelPricingIntegrationTest`：7项，包括实际 ingest→worker→费用证据、自定义优先、私有隔离、时区和跨午夜边界、历史证据保留、未来生效、失效快照、同步失败和异常条目隔离。既有 `IngestIntegrationTest` 全部73项也通过，包含原报表/导出覆盖；本次未改导出列。
- `npm test`：18个文件、51项通过。
- `npm run build`：通过，生成11个公开页面。
- `node scripts/npm-audit.mjs`：在 Node 24 Docker 中通过，0项漏洞。Windows 本机脚本通过 `spawnSync('npm')` 未能启动 npm，未将其当作成功；改为同版本 Linux 容器完成真实审计。
- `node scripts/sync-doc-examples.mjs --check`、`node scripts/sync-scenario-examples.mjs --check`、`node scripts/write-sdk-id-guides.mjs --check`：全部退出码0。本次不改变 SDK/上报协议。
- `git diff --check`：通过。

## 真实价格源

拉取 `https://models.dev/api.json`，响应约5.95MB。使用生产解析器校验实际快照，得到5,595条支持的价格，包含 `deepseek/deepseek-flash`。首次发现部分异常费率会阻塞整批更新，已改为隔离该条目，并加入回归测试；标准化平台源仍严格拒绝无效完整快照。

在新的独立 Testcontainers PostgreSQL 中运行 V1–V23 迁移，再使用生产 `ModelPriceSync.sync()` 真实 HTTP 拉取并导入，两次均为 `status=ok, model_count=5595`。两次后总价格版本数仍为5,595，验证重复同步不制造版本。此测试不访问生产数据库，不向价格源传用户数据。输出：`.local/model-price-live-smoke.log`。

## UI

本地合成数据预览，1920×1080及390×844检查通过。窄屏页面宽375px，无页面横向溢出；公共匹配开关关闭后刷新仍为关闭；自定义分时价格提交后显示时区、跨午夜时段及对应费率。表格滚动限制在自身容器内。

截图：`.local/model-pricing-desktop.png`、`.local/model-pricing-mobile.png`。其中模型数、同步时间和自定义价格为 UI fixture 示例，不是生产数据。

## 范围

默认公共源不提供每日分时价格；系统支持平台标准化分时源和项目分时覆盖，不从缺失数据中猜测时段费率。复杂或不可表达的模型维持未知。公共源首见时间为默认版本生效时间，不回填未知的历史价格。实现与运行说明见 `docs/model-pricing.md`。
