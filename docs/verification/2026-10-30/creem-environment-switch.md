# Creem environment switch verification

- Full backend contract check: Linux Maven 3.9 / Java 17, `mvn -B verify` against backend working directory, Docker PostgreSQL/Redis integration dependencies: BUILD SUCCESS (2026-10-30 06:16 UTC). Log: `.local/creem-mode-linux-verify.log`.
- New environment selection tests: 2 passed, including complete test/live credential isolation, missing-config no fallback, invalid mode and wrong-environment key rejection.
- Native Windows verification failed due to Java HTTP client loopback socket `Invalid argument: connect`; this is not recorded as a successful run. Full Linux verification subsequently passed.
- Frontend `npm test`: 25 files / 74 tests passed.
- Frontend production build: passed, including generated public pages.
- `npm audit --audit-level=high` against npmjs: 0 vulnerabilities.
- Generated documentation, scenario and SDK ID guide synchronization: all three `--check` commands passed.
- `git diff --check`: passed.

No production environment switch or deployment performed in this verification. No real payment submitted.
- Browser UI checks: desktop and 390px narrow viewport; dropdown selection, last-option guard, Escape dismissal and export type URLs verified.
