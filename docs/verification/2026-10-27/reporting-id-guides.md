# Verification: reporting tutorials and score targets (2026-10-27)

Scope: public tutorial copy and runnable HTTP examples, REST/SDK reporting scenarios, bilingual generated SDK guides. No backend runtime or SDK contract change.

- Frontend: 43 tests / 15 files passed; production build passed (11 public pages).
- Dependency audit: 0 vulnerabilities (npm audit --audit-level=high, official registry).
- Generated examples: sync-doc-examples.mjs --check, sync-scenario-examples.mjs --check, write-sdk-id-guides.mjs --check passed.
- node --test sdk/node/test.mjs scripts/sync-doc-examples.test.mjs: 10 passed.
- check-scenario-examples.mjs: all 12 actual Node/Python/Java SDK examples passed local capture checks; both score targets checked against target objectId and REST fixtures.
- frontend/scripts/check-tutorials.mjs: all four published tutorials executed against local capture server; validated shared traceId, two events, current timestamps, user/customer/feature, 2100/2000 ms and 1500/400 tokens.
- Browser: desktop 1265px, no document horizontal overflow; Chinese REST target switch checked actual JSON for Trace and Observation; Java tutorial links resolve to /docs#protocol and /docs#sdk. Existing responsive styles reused; live 390px viewport unavailable in browser tool, so narrow-screen interaction not independently verified.
- Bilingual Markdown guides generated from the same code source, four scenarios per SDK language; narrative translated separately.
- Full local backend verification not run: no backend/report implementation changed. Exact commit CI required before deployment.

Command corrections: initial tutorial checker command used repository root instead of frontend; corrected path. Initial Bash path used C: instead of installed D:; corrected, all four tutorial checks then passed. These were local invocation errors, not product or CI failures.
