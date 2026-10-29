# Trace detail and request list layout verification

- `npm --prefix frontend test`: 22 files, 63 tests passed.
- `npm --prefix frontend run build`: TypeScript, Vite and 11 public pages passed.
- `python .local/audit-locked-dependencies.py`: 114 locked packages, zero advisories.
- Generated example checks: sync-doc-examples, sync-scenario-examples and write-sdk-id-guides with --check all passed.
- `git diff --check`: passed.
- Browser fixture: trace table starts with object ID then name, with no model column. Detail shows one Trace and two Observations within waterfall; separate detail cards removed. Full IDs, model, cost, usage, time/status/revision and expandable fields retained. At 390px no page horizontal overflow.
- Overview fixture: cards show only request name, timestamp and status; 62px row height; page size 10. Request duration and usage appear in right detail.

These are frontend checks; no backend or export contract changes. Exact commit CI remains a deployment gate.

Follow-up: removed the overview Trace-level model row and duplicate reported-fields expander. Frontend: 63 tests and production build passed; locked dependency audit reported zero advisories; all three generated-example checks and git diff --check passed.

ID/cache follow-up: 64 frontend tests passed, production build passed, audit zero advisories, generated examples synchronized, diff check passed. Fixture browser verified ID precedes name/type, no duplicate type/status subtitle, no overflow at desktop or 390px. Cached usage aggregation tested for multiple calls, unknown values and measured zero. Trace table now displays the full object ID with copy retained.

Observation metrics follow-up: full mvn -B -f backend/pom.xml verify passed using JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:/code/llm-usage-monitor/.local/java-tmp -Duser.timezone=UTC; original local attempts failed due Windows short TEMP Unix socket path and local timezone in billing tests. Frontend 64 tests, build, 114-package audit (zero advisories), all three generated-example synchronization checks passed. Slow/failed counters now exclude Trace and Score and include Observation; 4999/5000ms boundary covered.

Observation filter/event IDs follow-up: full mvn -B -f backend/pom.xml verify passed with UTC and explicit local Unix socket temp directory. Observation filter regression proves 30s root with five sub-5s children excluded; 5000ms child included; failed child included regardless root status; invalid filter/ownership checked. IngestIntegrationTest verifies current revision event ID persists after worker materialization. Frontend 64 tests, production build, dependency audit (0 findings), three generated sync checks and diff check passed. V26 adds persistent object event ID and backfills retained matching revisions; unavailable history stays null.

## 状态换行及模型去重
- frontend test：22 files / 64 tests passed。
- frontend production build：passed。
- audit-locked-dependencies.py：114 packages，0 advisories。
- sync-doc-examples、sync-scenario-examples、write-sdk-id-guides --check：passed。
- git diff --check：passed。
- 本次为前端展示调整；浏览器本地预览连接失败，未将其记录为视觉验证通过。

## 费用公式及 Trace 子记录展开
- 前端展示改动，复用现有 traces API 的 event_id、费率和 Observation 数据；未修改后端或导出契约。
- npm --prefix frontend test：22 files / 65 tests passed，新增缓存输入公式及未知价格回归。
- npm --prefix frontend run build：passed（含 vue-tsc、Vite、11 public pages）。
- 三项 generated examples --check：passed。
- audit-locked-dependencies.py：114 packages，0 advisories。
- git diff --check：passed。
- 窄屏保持容器横向滚动，未声称完成浏览器视觉验收。

## Observation 展开区域层级优化
- Trace 展开底色、Observation 分组标题及数量、状态标签、数字右对齐、窄屏内滚动。
- frontend test：22 files / 65 tests passed；production build：passed。
- 三项 generated examples --check：passed；依赖审计114 packages / 0 advisories；git diff --check：passed。
- 未修改接口或导出契约；未声明浏览器视觉验收通过。

## 费用公式悬浮提示
- 移除常驻公式区；金额后问号支持悬停、聚焦和点击显示，Escape 收起点击状态，切换请求重置。
- frontend test：22 files / 65 tests passed；production build passed；三项示例同步检查 passed；依赖审计114 packages / 0 advisories；diff check passed。
- 未修改计价及历史费用，未声称浏览器视觉验收通过。

## 概览费用说明精简
- 移除请求详情费用下的已定价条数说明。
- frontend test 22 files / 65 tests passed；build passed；三项示例同步检查 passed；依赖审计114 packages / 0 advisories；diff check passed。

## 父子列对齐及 Observation Token
- 父子表共享11列宽度；子表Token为输入+输出，缓存不重复计入，缺失用量保持未知；窄屏统一容器滚动。
- frontend test：23 files / 67 tests passed；build passed；三项示例同步检查 passed；依赖审计114 packages / 0 advisories；diff check passed。
- 未修改后端或导出契约；未声称浏览器视觉验收通过。

## 公式浮层尺寸调整
- 扩大浮层，模型与Observation ID同行；23 files / 67 tests passed，build、三项示例同步、114 packages依赖审计0 advisories及diff检查通过。

## 详情视觉及只读Trace评分
- 五项指标卡、瀑布图分区及状态标签；只展示目标为当前Trace的Score，默认0不保存，多项按事件时间最新优先。
- frontend test：24 files / 69 tests passed；build passed；三项示例同步检查 passed；依赖审计114 packages / 0 advisories；diff check passed。
- 新增默认0、错误目标排除、最新真实0评分回归；未声称浏览器视觉验收通过。

## 数据统计三块布局
- 左侧分组列表，右侧Token及费用两张独立柱状图；当前维度明确显示字段，说明分组而非请求逐条列表。后端与导出契约未改变。
- frontend test：24 files / 69 tests passed；build passed；三项示例同步检查 passed；依赖审计114 packages / 0 advisories；diff check passed。未声称浏览器视觉验收通过。

## 套餐页及消息界面精简
- 移除套餐顶部静态说明与订阅状态区域，保留支付确认交互；调整列表列宽、评分说明及消息页面样式。
- frontend test 24 files / 69 tests passed；production build passed；三项 generated-example synchronization passed；dependency audit 114 packages / 0 advisories；git diff --check passed。
- 本批未完成浏览器视觉验收，以上不视为视觉验收。
