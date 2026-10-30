# Export and console verification

- Backend: `mvn -B verify` in `maven:3.9-eclipse-temurin-17`, with backend mounted at `/workspace` and Docker socket for Testcontainers: PASS, 235 tests, zero failures/errors/skips. Log: `.local/export-linux-final-verify.log`. Native Windows verify failed during HttpClient loopback initialization; it was not counted as a pass.
- Frontend: `npm --prefix frontend test`: PASS, 25 files / 74 tests.
- Frontend production: `npm --prefix frontend run build`: PASS, including public-page generation. Used the local clean npm CLI because the installed npm failed to initialize.
- Dependency audit: `npm audit --registry=https://registry.npmjs.org --audit-level=high --fetch-retries=0 --fetch-timeout=20000`: PASS, zero vulnerabilities. Mirror audit endpoint returned 404; official registry supplied the successful evidence.
- Generated examples: `node scripts/sync-doc-examples.mjs --check`, `node scripts/sync-scenario-examples.mjs --check`, `node scripts/write-sdk-id-guides.mjs --check`: PASS.
- IngestIntegrationTest covers Trace-only, Observation-only and both types in Excel/PDF, hierarchy, headers, all-row pagination and ownership. Wide PDF page geometry is checked.
- Browser fixture: both types selected initially, single selection updates export URLs, last selected option cannot be cleared. Desktop inline controls and 390px layout inspected.
- Existing report exporters reviewed: analytics grouping header now follows selected dimension; shared PDF width follows report columns to prevent clipping.
- `git diff --check`: PASS.

Push CI and production deployment are separate checks and are not represented by these local results.
