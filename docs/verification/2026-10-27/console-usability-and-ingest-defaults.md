# Console usability and optional ingestion metadata verification

Date: 2026-10-27.

## Scope

- Responsive trace cards, readable desktop trace cells, full-ID copying, explicit unknown-cost state, quick date queries, 25-row remembered pagination, singular/empty pagination, visible ingestion pause state and one-time-key quickstart examples.
- Accept page size 25 in report, insight, administrator and support endpoints; retain legacy size 5 compatibility.
- Previously pending requested ingestion contract: optional schemaVersion/agent/revision defaults, arbitrary 36-character event IDs, additive V20 identity migration, updated public examples and Chinese request references.
- Unrelated local mail delivery/configuration changes are excluded.

## Commands and results before commit

- `mvn -B -f backend/pom.xml verify` via `maven:3.9-eclipse-temurin-17`, Docker socket and Testcontainers host override: **180 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**, 3m07s. Executed in `.local/id-validation`, whose changed backend sources/tests were checked to match this submission; excludes unrelated local mail changes. Log: `.local/console-usability-backend.log`.
- `IngestIntegrationTest`: 71 passing tests including 5/10/25/50/100 pagination, complete XLSX/PDF export/header/ownership coverage, omitted-revision equivalence, arbitrary IDs, and legacy UUID-trigger compatibility.
- `npm test` in frontend: **32 tests across 12 files passed**, including saved page-size validation, unavailable storage, empty-list exports, English singular row and custom-date controls.
- `npm run build` in frontend: **passed**, including TypeScript and public-page generation.
- Clean npm CLI audit against `https://registry.npmjs.org`, high-severity gate, zero retries and 20-second request timeout: **0 vulnerabilities**. Command: `node ../.local/clean-npm/package/bin/npm-cli.js audit --audit-level=high --json --registry=https://registry.npmjs.org --fetch-retries=0 --fetch-timeout=20000`.
- `node scripts/sync-doc-examples.mjs --check`: passed.
- `node --test sdk/node/test.mjs scripts/monitor-gate.test.mjs scripts/release-rollback.test.mjs scripts/load-protocol.test.mjs scripts/npm-audit.test.mjs scripts/sync-doc-examples.test.mjs`: **26 passed**.
- Python SDK unittest discovery: **6 passed**.
- `node scripts/check-tutorials.mjs` in frontend with Git Bash/Python paths: **Node, Python and REST published examples all returned HTTP 200 / pending** against the local capture server.
- Java SDK `mvn -B -f sdk/java/pom.xml verify`: passed during this contract change; log `.local/optional-sdk-verify.log`. No later Java SDK source changes.
- Both Chinese Markdown complete-request JSON blocks parsed successfully with three event types and 36-character IDs.
- `git diff --cached --check`: passed.

## Limits and remaining verification

- Browser visual/interaction QA at desktop and 390px is **not complete**. Windows Computer Use stopped because it could not confidently determine the browser URL; no bypass attempted. Build and server-rendered tests are not visual QA.
- No authenticated production ingestion, real key creation, email, payment, or deployment is performed by these checks.
- Exact pushed commit must still pass GitHub backend, frontend-sdk, security and images jobs. Local checks do not establish those results. Deployment remains blocked while any required check is pending/failed/skipped/unavailable and while visual acceptance remains outstanding.
