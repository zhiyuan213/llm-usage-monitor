# Score display verification

- Frontend tests: `npm.cmd --prefix frontend test` — 24 files, 72 tests passed.
- Production build: `npm.cmd --prefix frontend run build` — passed; existing bundle-size warning remains.
- Generated examples: `node scripts/sync-doc-examples.mjs --check`, `node scripts/sync-scenario-examples.mjs --check`, `node scripts/write-sdk-id-guides.mjs --check` — passed.
- `npm.cmd --prefix frontend audit --audit-level=high --json` — local npm runtime failed with `Class extends value undefined is not a constructor or null`; not recorded as scan success.
- Official npm registry bulk advisory fallback (`.local/audit-locked-dependencies.py`): 114 locked packages, 0 advisories.
- `git diff --check` — passed.
- Observation score tests cover target type/ID isolation, latest result, reported zero/false and unreported default zero. Existing backend detail response already includes matching Score objects; no API/report export contract changed.
- Exact-commit backend/frontend-sdk/security/images CI required before deployment.
`nFollow-up: clear expanded Observation rows when page/project changes; repeat frontend tests (72 passed), build, generated checks and registry audit (114 packages, 0 advisories) passed.
