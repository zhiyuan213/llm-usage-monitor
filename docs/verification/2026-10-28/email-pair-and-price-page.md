# Email verification pair and independent public prices page

2026-10-28, local pre-commit verification.

- Project settings no longer embeds the public price catalogue. Overview and settings links open /app/model-prices, carrying project and return location. Back returns to overview/settings; direct entry falls back to project settings. Existing search, pagination, source and time-band details retained.
- One email-change request without action issues old/new challenges and queues two separate recipient messages in a single database transaction. Existing explicit action requests remain compatible. Per-user 60-second cooldown is checked once under the user row lock; both addresses still require valid independent challenges. The UI has one send button and clears stale code fields.
- `mvn -B -f backend/pom.xml verify` via Docker Maven/Java17: BUILD SUCCESS, 219 tests, zero failures/errors/skips. Updated IngestIntegrationTest exercises the paired HTTP response, distinct challenge IDs, both mailed codes, repeat-send 429 without extra challenges, successful email change and session revocation. Log .local/email-pair-verify.log.
- Frontend Vitest: 21 files / 61 tests passed. vue-tsc, Vite production build and 11 public pages passed. Logs .local/email-pair-frontend-{test,build}.log.
- Locked npm dependency advisory check: 114 package names, zero advisories. All three generated-example synchronization checks passed.
- Local browser fixture: settings catalogue absent; price page renders; pagination reaches page 2; Back returns to settings; overview entry targets independent page and Back targets overview. Narrow-screen table retains internal scrolling. Account page shows one bilingual send button; narrow button remains inside viewport.
- No real verification emails sent during local browser checks. Integration tests inspect synthetic outbox messages.
