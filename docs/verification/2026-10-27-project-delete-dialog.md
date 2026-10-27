# Project list deletion dialog — 2026-10-27

- Move Delete project into each project's More menu. Open the existing verification modal with the selected row's stable project ID and name, an exact-name confirmation field, impact notice, and email verification. Preserve backend ownership, CSRF and verification enforcement. Remove the old settings-page delete form.
- Local verification: frontend tests 47/47 across 16 files; production build success; npm audit 0 vulnerabilities; all three generated-example synchronization checks success.
- Browser fixture: More opens dialog without navigation; empty/wrong name disables code sending and deletion; matching name enables code sending while deletion still requires a verification challenge. Desktop and 390x844 layouts checked. No real project deleted.
- Commands: npm --prefix frontend test -- --run; npm --prefix frontend run build; node .local/clean-npm/package/bin/npm-cli.js --prefix frontend audit --audit-level=high --registry=https://registry.npmjs.org --fetch-retries=0 --fetch-timeout=20000; node scripts/sync-doc-examples.mjs --check; node scripts/sync-scenario-examples.mjs --check; node scripts/write-sdk-id-guides.mjs --check.
- Backend contracts unchanged; no new backend verification claimed. Exact pushed CI and deployment status must be inspected separately.
