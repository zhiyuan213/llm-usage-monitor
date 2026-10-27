# Overview metrics and deletion confirmation — 2026-10-27

- Overview shows requests, slow Trace requests (>= 5000 ms), failed Trace requests, tokens and known model cost. Removed P95 and trend/model-distribution blocks.
- Project deletion requires exact lowercase `delete` and the existing email verification.
- Full backend: Linux Docker `maven:3.9-eclipse-temurin-17 mvn -B verify` with backend mounted at /work, Maven cache and Docker socket: BUILD SUCCESS; 182 tests, 0 failures/errors/skips. Windows Maven verify and IPv4 retry could not initialize loopback HttpClient (SocketException); these were not counted as passing checks.
- Frontend: npm --prefix frontend test: 16 files / 47 tests passed; npm --prefix frontend run build: passed (11 public pages).
- Dependency audit: node .local/clean-npm/package/bin/npm-cli.js --prefix frontend audit --audit-level=high --registry=https://registry.npmjs.org --fetch-retries=0 --fetch-timeout=20000: 0 vulnerabilities.
- node scripts/sync-doc-examples.mjs --check; node scripts/sync-scenario-examples.mjs --check; node scripts/write-sdk-id-guides.mjs --check: all passed.
- Local browser fixture: 1920x1080 five cards in one row; 390x844 stacked cards, no document horizontal overflow. Delete dialog Send code disabled for wrong text, enabled for exact delete; no deletion or email sent.
- git diff --check: passed. Exact pushed commit CI must pass backend, frontend-sdk, security and images before deployment.
