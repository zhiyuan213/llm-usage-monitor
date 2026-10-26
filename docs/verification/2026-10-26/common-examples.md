# Common-field example acceptance

Problem: the first REST body deliberately removed identity fields and its linked generation. Copying it produced a Trace with no model usage, so the call list had empty model, Token, user and customer fields.

Change: default REST body, executable Node/Python examples, generated batch shared by cURL/Java/Go/C#/PHP, and all three tutorials now include a Trace and linked generation. Document identity keys, matching pricing prerequisites, non-additive cached input, two-unit accounting, and demonstration prices explicitly. Do not manufacture cost without pricing or rewrite historical records.

Pre-commit checks (2026-10-26):
- `npm test` in frontend: 29 tests / 11 files passed.
- `npm run build` in frontend: typecheck, production bundle and six public HTML pages passed.
- `node scripts/sync-doc-examples.mjs --check` from repository root: passed.
- `node --test scripts/sync-doc-examples.test.mjs`: passed (LF/CRLF synchronization).
- `node scripts/check-tutorials.mjs` in frontend, with explicit local Python and POSIX shell: all three published programs sent HTTP 200 / pending; capture assertions require linked generation, identities and complete Token usage.
- Official-registry `npm audit --audit-level=high --json --fetch-retries=0 --fetch-timeout=20000`, using existing clean local npm CLI: zero vulnerabilities. System npm's minipass-flush dependency failed to load; this was a local npm installation error, not successful security evidence. Clean npm completed the actual audit.
- Browser: desktop 1920 and narrow 390 checked. Two-column field mapping wraps at 296px with no page horizontal overflow; default payload shows identities and generation; receipt count is two. No real API key, payment, email or production test data was used.

Deployment requires all four exact-commit GitHub Actions jobs to succeed.
