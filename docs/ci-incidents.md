# CI failure records

## 2026-10-22 npm audit service outage

- Affected commit: `3dd58ae`, run `39739944051`, job `119694467787`.
- Frontend tests (5) and Vite production build passed. The subsequent npm audit request to `https://registry.npmjs.org/-/npm/v1/security/advisories/bulk` returned HTTP 503 after five minutes. Backend and security jobs passed; images were skipped because the frontend job failed.
- Root cause: external audit service unavailability, not a frontend compilation or test regression.
- Fix: separate build/test and audit steps. Run audit with at most three attempts, a 20-second fetch timeout and a 30-second process timeout per attempt; wait 5/10 seconds between transient failures.
- Only transient network/HTTP 429 and 5xx failures retry. A valid vulnerability report, configuration errors, or exhausted retries still fail CI. High/critical vulnerability gates remain enabled.
- Regression checks cover recovery after 503, persistent outage, unexpected errors and vulnerability reports containing a misleading 503 URL.
- Before delivery: run tests/build locally, inspect the latest GitHub run for the exact pushed commit, and confirm all required jobs pass. Local build success alone does not prove audit, SDK checks or image jobs passed.

## Earlier security failures

- Invalid Trivy Action release reference: fixed by pinning a verified existing Action commit, not disabling the security job.
- PostgreSQL JDBC vulnerability: upgraded driver to 42.7.14; do not suppress genuine vulnerability findings to make CI green.
