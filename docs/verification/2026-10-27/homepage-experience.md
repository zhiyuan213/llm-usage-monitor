# Public homepage performance and onboarding

Date: 2026-10-27. Frontend/static-server changes only; unrelated mail delivery and environment changes are excluded.

## Changes

- Self-hosted Chinese UI subset: 4,193,120 -> 100,444 bytes. Original font/license retained. Rebuild with `scripts/subset-font.py` and fonttools[woff]. English public pages use system fonts; the CJK face has unicode-range and font-display: swap. Characters outside the subset use system fallback.
- Static asset gzip and Vary; API/authenticated responses are not covered by the new compression location.
- Shorter hero, pricing unit examples, standalone tutorial links in quickstart, mobile navigation toggle and working product/units anchors.
- Optional analytics consent is in document flow, with explicit decline-on-close and reopen preferences.
- Security guidance/static HTML/sitemap entry reflects existing controls, without invented customer statistics, certification or availability claims.
- Operator identity/About and finalized legal policies deferred at the user's request. Existing policy warnings remain.

## Verification before commit

- `npm test` (frontend): 36 tests / 13 files passed. Includes pricing arithmetic, standalone quickstart, security guidance, consent controls, and existing analytics opt-in/privacy tests.
- `npm run build` (frontend): TypeScript and Vite passed; generated 7 public static pages.
- `node ../.local/clean-npm/package/bin/npm-cli.js audit --audit-level=high --json --registry=https://registry.npmjs.org --fetch-retries=0 --fetch-timeout=20000` (frontend): 0 vulnerabilities.
- `node scripts/check-tutorials.mjs` (frontend with Git Bash/Python paths): Node, Python and REST examples returned HTTP 200 / pending against local capture server.
- `node scripts/sync-doc-examples.mjs --check` (repository root): passed. An initial invocation from frontend used the wrong working directory; corrected root invocation passed without changing generated examples.
- `python scripts/check-web-image.py`: passed using real production Dockerfile, restrictive 0600/0700 source permissions and non-root nginx UID 101. Public HTML/assets/font, security page, sitemap and existing private noindex/CSP checked.
- Real container requests with Accept-Encoding gzip: JS 407,781 -> 141,392 bytes; CSS 87,207 -> 18,043 bytes. Vary: Accept-Encoding, decompressed identity and security headers verified. Log `.local/homepage-image-check.log`.
- `git diff --check`: passed.

## Limits

- Desktop/mobile browser visual acceptance is incomplete: Windows Computer Use stopped because it could not reliably identify the current browser URL. No bypass attempted. SSR tests/build/runtime checks do not establish visual acceptance, English-page network font absence, Lighthouse scores or mobile LCP.
- Public TLS HTTP/2/Brotli/latency not changed or certified. Compression sizes above are local container measurements, not public-network timings.
- No backend changes in this commit, and no new backend capacity certification claimed.
- After push, inspect backend, frontend-sdk, security and images jobs for this exact commit. Pending/failed/skipped/unavailable jobs and outstanding visual acceptance block deployment.
