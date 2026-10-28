# Pro quota, Lombok examples and payment toolbar verification

2026-10-28 local verification before implementation commit:

- `mvn -B -f backend/pom.xml verify` in Maven/Java17 Docker with PostgreSQL Testcontainers: PASS, 206 tests, zero failures/errors/skips. Includes Pro quota API and durable admission at 999,999 / 1,000,000 units; Free/Core quotas unchanged.
- `mvn -B -f examples/rest/java/pom.xml clean verify`: PASS, Lombok annotation processing and nullable usage serialization tested.
- `node node_modules/vitest/vitest.mjs run`: PASS, 20 files / 59 tests.
- `node node_modules/vue-tsc/bin/vue-tsc.js -b`, `node node_modules/vite/bin/vite.js build`, `node scripts/build-public-pages.mjs`: PASS, production bundle and 11 public pages.
- Official npm registry bulk advisory audit of all 114 distinct locked package names: zero advisories. Local npm CLI is broken; this is registry audit evidence, not a successful npm CLI invocation. CI npm audit remains required.
- `node scripts/sync-doc-examples.mjs --check`, `node scripts/sync-scenario-examples.mjs --check`, `node scripts/write-sdk-id-guides.mjs --check`: PASS.
- `node --test scripts/sync-doc-examples.test.mjs`: PASS.
- `node scripts/check-tutorials.mjs` from frontend: PASS for exact published Java, Node, Python and REST examples, HTTP 202 success and HTTP 400 rejection.
- Browser desktop payment toolbar padding 20px 24px; narrow viewport padding 20px 16px, document client/scroll width both 300px, no page overflow. Search remains a normal form; refund behavior unchanged. Public pricing shows Core 100,000 / Pro 1,000,000.
- Overage rates remain unchanged; pricing table now labels billable overage units instead of Core-specific absolute usage thresholds.

Push CI and deployment are separate required gates; these local results alone do not establish deployment.
