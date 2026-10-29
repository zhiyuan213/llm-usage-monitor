# CI failure records

## 2026-10-28 new dependency advisories block email/price-page release

- Commit `c6c6e98`, run `40707348467`: Trivy failed with 10 HIGH/CRITICAL findings in the existing Spring Boot 3.5.16 dependency tree (Jackson databind, Netty handler, Tomcat core and Spring MVC). Gitleaks passed. This is a genuine dependency gate, not a transient scan outage; deployment remained blocked.
- Spring's primary advisories for CVE-2026-47884 and CVE-2026-47890 list public fixes in Spring Framework 7.0.9 and enterprise-only fixes for 6.2.20. Maven Central has no newer public 6.2 patch, so the fix moves to Boot 4.0.9's managed Spring 7.0.10 / Tomcat 11.0.27 / Netty 4.2.19 / Jackson 2.21.7 combination rather than ignoring findings.
- Preserve the JSON protocol using Boot's documented `spring-boot-jackson2` compatibility module, `spring.http.converters.preferred-json-mapper=jackson2`, and `spring.jackson2.deserialization.fail-on-trailing-tokens=true`. Adapt Boot's moved JDBC/security/MockMvc packages and replace removed MockBean/SpyBean with MockitoBean/MockitoSpyBean. Testcontainers stays on 1.21.4 to retain its existing API.
- Local verification: full backend verify passed 220 tests with zero failures/errors/skips; Trivy 0.70.0 backend dependency scan found zero HIGH/CRITICAL findings.
- Prevention: rerun full backend contract tests and inspect the exact follow-up commit's dependency scan, SDK and image checks before release. No CVE suppressions or severity reductions were added.
- References: https://spring.io/security/cve-2026-47884/ ; https://spring.io/security/cve-2026-47890/ ; https://docs.spring.io/spring-boot/4.0/reference/features/json.html#features.json.jackson2


## 2026-10-28 policy date duplicated in web-image acceptance

- Commit `810f52f`, run `40622969112`: backend, frontend-sdk and security passed; images failed at `/terms` in `check-web-image.py`.
- Root cause: the cancellation policy updated its published date to 2026-10-28, while image acceptance independently hard-coded 2026-10-27. The frontend policy regression was updated, but the image contract was missed.
- Fix: image acceptance reads the required ISO policy date from the canonical `policy-content.ts` source, rejects a missing date and verifies that all three built policy pages actually contain that date. Operator identity and template-removal checks remain blocking.
- Prevention: run the production web-image acceptance whenever changing public policy content, dates or generated pages; preserve the date assertion rather than weakening it to permit arbitrary stale pages. Deployment of `810f52f` remained blocked after the failed image check.
- Local fix verification: `python scripts/check-web-image.py` passed, including non-root UID 101, restrictive 0600/0700 build context, all three policy pages, retained assets, private-page headers and proxied gzip byte checks. No application source changed in this follow-up.

## 2026-10-27 retained-assets acceptance fixture permissions

- Commit `fb81119`, run `40468486687`: backend, frontend-sdk and security passed; images failed in `check-web-image.py` when requesting the previous-release fixture (HTTP 404).
- Root cause: Python TemporaryDirectory defaults to mode 0700 on Linux. Non-root nginx could not traverse the mounted fixture directory. Windows local verification did not expose the POSIX permission difference.
- Fix already shipped in `e25c096`: explicitly set fixture directories to 0755 and files to 0644, matching the production archive preparation script.
- Prevention: test mounted fixture access under the actual UID 101 image on Linux CI; retain restrictive source-context tests. Exact fixed commit run `40468559956` passed backend, frontend-sdk, security and images before deployment. Confirmed again when investigating this notification; the email concerns the earlier failed commit, not a new production failure.

## 2026-10-27 cached entry references removed release assets

- Production screenshot showed a blank console: old hashed JS returned 404 and CSS was served as HTML. Current HTML had no explicit Cache-Control, and each web image replaced previous assets.
- Fix: no-store for resolved HTML, persistent read-only asset archive and a release preparation script that copies previous and candidate hashes before recreation. Recovery imports retained release images without changing application data.
- Prevention: production-image acceptance tests cover old JS/CSS exact bytes and MIME type, unknown hash 404, HTML cache policy, security headers and compression through Via. Every web release must retain assets before replacing the container; preserve the archive on rollback.

## 2026-10-27 static compression behind the public reverse proxy

- Commit `88b6dbb` passed local non-root image checks and all CI jobs, but public requests through Caddy returned uncompressed JS/CSS despite requesting gzip. Direct localhost Nginx requests compressed correctly; public HTTP/2 was already enabled.
- Root cause: Nginx's default `gzip_proxied off` suppresses compression when the upstream request includes a Via header. The initial image test only exercised a direct client.
- Fix: enable `gzip_proxied any` only in the static `/assets/` location; keep API/authenticated responses outside that compression scope.
- Prevention: production-image tests now request JS/CSS with both Accept-Encoding: gzip and Via: 1.1 acceptance-proxy, asserting encoding, Vary, byte-identical decompression and retained security headers. Public post-deploy verification must also request gzip through the real TLS proxy.

## 2026-10-25 trace export schema expanded with usage summaries

- Commit `68ff940`, run `40199162437`: backend failed the retained-row export regression; frontend-sdk and security passed, images were skipped.
- Root cause: trace exports now append model, known Token, known estimated cost and unknown-cost count columns, but the existing spreadsheet regression still expected seven columns instead of eleven.
- Fix: retain the original seven columns and append the four summaries; update the regression to require all eleven headers, including their explicit Chinese names. Keep the existing all-row, pagination, PDF and ownership checks.
- Prevention: include the complete export integration regression when changing list/report schemas, alongside targeted metric, date-boundary and sort-validation tests. Deployment remains blocked until the exact final commit passes every required CI job.
- Follow-up checked on 2026-10-26: this notification refers to the historical failure, not a new failure of the deployed version. Fix commit `c4da4a4` updates the trace export expectation to eleven columns and asserts the four new Chinese headers. Current deployed commit `17dfc62762981f02a4ed3405fe54b6228377843f`, run `40201422533`, has successful backend, frontend-sdk, security and images jobs. Repository instructions now explicitly require contract-sensitive local checks before every implementation commit and exact-commit CI verification after each push.

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

## 2026-10-29 local payment fixture timestamp precision
- Full backend verification exposed `wrongPriceDeclineAndLatePaymentNeverGrantPack`: expected refund_required, observed failed. Sequential synthetic webhook events used wall-clock nanoseconds; PostgreSQL microsecond rounding could make a later event compare as non-newer and be ignored.
- Fix test fixtures to use monotonically increasing, one-second-separated event times. Production event ordering and payment logic stay unchanged. Keep the full late-payment/refund assertions and full verify regression.

## 2026-10-30 public home static copy drift
- Release 4401a71 passed CI but post-deploy content acceptance found the generated home.html still used the previous hero. Automatic rollback restored the previous web and worker images.
- Root cause: Vue public copy and build-public-pages.mjs maintain separate home content. Sync the static title/body/description with the confirmed positioning. Pricing currently renders on the client, so initial HTML cannot verify its FAQ; verify its bundle plus browser-rendered content instead.
- Prevention: inspect generated dist/home.html after every home copy change and retain exact content checks in post-deploy verification.
