# CI failure records

## 2026-10-25 trace export schema expanded with usage summaries

- Commit `68ff940`, run `40199162437`: backend failed the retained-row export regression; frontend-sdk and security passed, images were skipped.
- Root cause: trace exports now append model, known Token, known estimated cost and unknown-cost count columns, but the existing spreadsheet regression still expected seven columns instead of eleven.
- Fix: retain the original seven columns and append the four summaries; update the regression to require all eleven headers, including their explicit Chinese names. Keep the existing all-row, pagination, PDF and ownership checks.
- Prevention: include the complete export integration regression when changing list/report schemas, alongside targeted metric, date-boundary and sort-validation tests. Deployment remains blocked until the exact final commit passes every required CI job.

## 2026-10-22 npm audit service outage

- Affected commit: `3dd58ae`, run `39739944051`, job `119694467787`.
- Frontend tests (5) and Vite production build passed. The subsequent npm audit request to `https://registry.npmjs.org/-/npm/v1/security/advisories/bulk` returned HTTP 503 after five minutes. Backend and security jobs passed; images were skipped because the frontend job failed.
- Root cause: external audit service unavailability, not a frontend compilation or test regression.
- Fix: separate build/test and audit steps. Run audit with at most three attempts, a 20-second fetch timeout and a 30-second process timeout per attempt; wait 5/10 seconds between transient failures.
- Only transient network/HTTP 429 and 5xx failures retry. A valid vulnerability report, configuration errors, or exhausted retries still fail CI. High/critical vulnerability gates remain enabled.
- Dependency installation uses `npm ci --no-audit` in CI and the image build, so installation does not duplicate an unbounded implicit audit request. The separate explicit audit job remains mandatory and uses the bounded retry policy above.
- Regression checks cover recovery after 503, persistent outage, unexpected errors and vulnerability reports containing a misleading 503 URL.
- Before delivery: run tests/build locally, inspect the latest GitHub run for the exact pushed commit, and confirm all required jobs pass. Local build success alone does not prove audit, SDK checks or image jobs passed.

## Earlier security failures

- Invalid Trivy Action release reference: fixed by pinning a verified existing Action commit, not disabling the security job.
- PostgreSQL JDBC vulnerability: upgraded driver to 42.7.14; do not suppress genuine vulnerability findings to make CI green.

## 2026-10-23 generated REST examples line endings

- Commit `0f158c0`, run `39822007791`: frontend-sdk failed generated-example synchronization; backend/security passed and images were skipped.
- Root cause: Windows REST source files contained CRLF. Generation embedded those characters into JSON strings; Git normalized the source files to LF, so Linux generated different documentation.
- Fix: normalize source and generated-file line endings to LF in the synchronization script, regenerate examples, and retain the mandatory CI check.
- Prevention: regression test generates from CRLF fixtures, changes them to LF, and requires the same output and a passing check.

## 2026-10-24 synthetic integration-test secret flagged

- Commit `f65ece8`, run `40070402589`: gitleaks flagged the literal Spring test-only `metertrail.secret` fixture in `DurableIngestIntegrationTest.java:30`.
- Root cause: the new descriptive synthetic fixture crossed the generic API-key entropy heuristic. The value is defined only in test source, not read from an environment or a deployed credential.
- Fix: reuse the existing clearly synthetic integration-test fixture. Since CI scans Git history, allow only the exact historical finding fingerprint in `.gitleaksignore`, with its test-only provenance documented; no rule or source-path exclusions.
- Prevention: run the same gitleaks v8.28.0 full-history scan locally, keeping all other findings blocking. Full CI, including Trivy and images, remains required.

## 2026-10-24 non-root frontend image inherited restricted checkout modes

- Commit `4ebf867` passed build-only CI, but the production web container restarted with `open() /etc/nginx/nginx.conf failed (13: Permission denied)`; the previous web image was restored and public HTTP 200 confirmed. API and Worker remained healthy.
- Root cause: the remote deployment inherited umask 077. Git wrote newly changed Nginx configuration and public verification files with mode 0600. Docker COPY preserved the mode with root ownership, while runtime USER nginx could not read them. Existing local Nginx checks ran as root and did not reproduce this boundary.
- Fix: COPY both Nginx configuration files with explicit 0644 permissions; normalize public output directories to 0755 and files to 0644 before switching to USER nginx. Do not weaken the runtime user or operator backup permissions.
- Prevention: `scripts/check-web-image.py` creates a real build context with 0600 files / 0700 directories, builds the production Dockerfile, starts its default non-root user, checks nginx configuration and serves HTML, JS/CSS, font, verification, robots and sitemap files over HTTP. It also checks private-page noindex and retained CSP headers. The images CI job now requires this check before uploading artifacts.
