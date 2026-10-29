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
