# Audit log default page size

Audit logs now use an independent initial page size of 10. Saved page-size preferences from other reports no longer override this default. Manual page-size selection and pagination remain available; other report defaults and exports are unchanged.

Pre-commit verification:

- Frontend Vitest: 20 files / 59 tests passed (`node node_modules/vitest/vitest.mjs run` from frontend).
- Production build: `node node_modules/vue-tsc/bin/vue-tsc.js -b`, `node node_modules/vite/bin/vite.js build`, `node scripts/build-public-pages.mjs`: passed, 11 public pages generated.
- All three generated-example synchronization checks passed.
- Local Docker was unavailable and the system npm installation failed during configuration/module loading. Dependency audit was performed directly against the official npm registry bulk advisory endpoint for all 114 distinct locked package names: HTTP 200, zero advisories (`.local/audit-default-registry-audit.json`). This is not claimed as a successful local npm CLI run. Exact-commit CI must still pass its normal npm audit before deployment.
- Browser fixture: initial request `page=0&size=10`, ten rows visible; next page contains one row out of eleven; changing size to 25 resets to page zero and shows all eleven. Screenshot `.local/audit-default-ten.png`.
- No backend or export contracts changed. Required exact-commit backend, frontend-sdk, security and images CI remain deployment gates.
