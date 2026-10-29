# Cost calculation and Trace score verification

Local verification completed on 2026-10-30. No push, CI result or deployment is claimed.

- `mvn -B -f backend/pom.xml verify`: PASS, 233 tests, zero failures/errors/skips. Includes IngestIntegrationTest Excel/PDF headers, full rows, pagination, ownership and latest targeted Trace Score coverage. Log: `.local/cost-engine-verify.log`.
- `npm --prefix frontend test`: PASS, 24 files / 70 tests. Log: `.local/cost-frontend-test.log`.
- `npm --prefix frontend run build`: PASS, including 11 generated public pages. Existing bundle-size warning remains. Log: `.local/cost-frontend-build.log`.
- Locked frontend dependency advisory audit: 114 packages, zero advisories, using `.local/audit-locked-dependencies.py`.
- `node scripts/sync-doc-examples.mjs --check`, `node scripts/sync-scenario-examples.mjs --check`, `node scripts/write-sdk-id-guides.mjs --check`: PASS.
- SDK checks: Node 9, Python 6, Java 4 tests passed; logs `.local/cost-node-sdk.log`, `.local/cost-python-sdk.log`, `.local/cost-java-sdk.log`.
- `git diff --check`: PASS (line-ending notices only).
- Browser fixture at localhost:14193: special billing rule add/remove fields checked at desktop and 390px width. Trace and Observation header x coordinates match for all 12 columns. At 390px, document width is 375px and the 1400px table scrolls within its container. Temporary viewport override reset.

Score displays the latest retained Score targeting the Trace object, with the same tie ordering as detail view; absence displays 0. Observation has no rating cell. Cost rules preserve stored historical costs. Native usage adapters are restricted to documented numeric text-generation usage; no currency conversion, invoice reconciliation or guessed pricing multipliers. Special rate cards combined with time bands are rejected until a combined contract is supported. See `docs/cost-calculation.md`.
