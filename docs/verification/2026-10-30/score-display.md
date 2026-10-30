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
Overview metric links call the existing request filter; View more carries project/date range; trace Observation dropdown uses existing API observation filter for list and export. Analytics guidance moved above query controls into a scoped card. Repeated 72 frontend tests/build/example sync and official registry bulk audit passed.
Waterfall follow-up: IDs moved below object name into detail typography; score metric removed, per-object scores shown alongside duration (Score rows use their own value). Repeated 72 tests, production build, generated synchronization and 114-package registry audit passed.
Score alignment follow-up: parent Trace and expanded Observation score headers/cells share center alignment. 24 test files/72 tests passed; production build and all three generated-example checks passed. Official registry bulk audit: 114 locked packages, 0 advisories. git diff --check passed.
Moved unknown-cost reason selector directly after cost completeness and before observation filter. Repeated frontend suite (72 tests), build, generated sync, registry audit passed.
