# Project settings consolidation — local verification

Changes: move API keys after project configuration, alerts below model pricing; remove both sidebar entries; redirect legacy routes with query preservation. Project Observation threshold defaults to 10 seconds and controls overview/list filtering. Trace observation costs support atomic manual correction with optimistic conflict checks and audit records.

- `docker run ... maven:3.9-eclipse-temurin-17 mvn -B -f backend/pom.xml verify`: PASS, 234 tests, zero failures/errors/skips, 3m39s. Includes IngestIntegrationTest export and ownership coverage and cost correction atomicity; ConsoleMetricsIntegrationTest threshold default, boundary, save, validation, CSRF and ownership checks.
- First full run found two assertions tied to the previous 5-second threshold. Updated fixtures to test 10,000ms inclusive boundary and 9,999ms exclusion; full rerun passed.
- `npm.cmd --prefix frontend test`: PASS, 73 tests across 24 files.
- `npm.cmd --prefix frontend run build`: PASS, production and 11 public pages. Existing bundle-size warning remains.
- `node scripts/sync-doc-examples.mjs --check`, `node scripts/sync-scenario-examples.mjs --check`, `node scripts/write-sdk-id-guides.mjs --check`: PASS.
- npm audit client crashes locally; official registry bulk audit of 114 locked dependencies via `.local/audit-locked-dependencies.py`: zero advisories. This does not replace required pushed security CI.
- Browser local fixture: legacy `/app/keys?lang=zh-CN` resolves to `/app/settings?lang=zh-CN#project-keys`; sidebar excludes keys/alerts, project configuration precedes keys, alerts follow prices; threshold dialog shows default 10 seconds. Desktop 1440px and narrow 390px layouts inspected, no document-width overflow at 390px. No real credentials created or revoked during UI checks.
- Not pushed or deployed as part of these local checks; exact-commit required CI remains a deployment gate.
