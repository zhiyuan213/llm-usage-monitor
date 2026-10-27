# Static entry and retained assets

Cause: HTML entry responses lacked an explicit cache policy, while replacing the web image removed the hashes referenced by older HTML. A cached entry could therefore fail to boot with JS 404 and an old server's HTML CSS fallback.

Changes: resolved HTML responses use no-store at server scope, preserving security headers. Hashed assets first use the current build, then a read-only retained-assets mount; absent hashes still return 404. Before each web recreation, run `bash scripts/retain-web-assets.sh PREVIOUS_IMAGE CANDIDATE_IMAGE` from the repository. Keep this archive across releases and rollbacks; do not prune it with build output. Existing images can populate it during recovery. No authenticated responses are archived.

Local verification before commit:

- `npm test` in frontend: 36 tests, 13 files passed.
- `npm run build` in frontend: passed; seven public pages generated.
- clean npm CLI audit, high threshold: zero vulnerabilities.
- `node scripts/sync-doc-examples.mjs --check`: passed.
- `python scripts/check-web-image.py`: passed, UID 101, restrictive source permissions; current assets and retained JS/CSS return exact content, missing asset returns 404; HTML no-store and CSP/private noindex retained; gzip checked with a proxy Via header.

No UI markup changed. Browser visual acceptance has not been performed for this infrastructure fix. Exact-commit CI remains required before deployment.
