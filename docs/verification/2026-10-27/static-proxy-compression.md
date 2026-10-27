# Static gzip through Caddy-compatible proxy headers

2026-10-27: production 88b6dbb is healthy, but public gzip checks exposed Nginx's default rejection of compression on Via-bearing proxy requests. See docs/ci-incidents.md. API and Worker health, V20 migration, private API 401 and public HTML 200 passed before this follow-up.

Fix scope: `gzip_proxied any` inside `/assets/` only. No application code, API response compression or dependency changes.

Pre-commit checks:

- `python scripts/check-web-image.py`: PASS with actual production Dockerfile, non-root UID 101, restrictive permissions, Accept-Encoding gzip and Via: 1.1 acceptance-proxy. JS 407781 -> 141392 bytes; CSS 87207 -> 18043 bytes. Decompression equality, Vary and security headers retained. Log `.local/homepage-proxy-image-check.log`.
- `npm test`: 36 tests across 13 files passed.
- `npm run build`: TypeScript/Vite/public static generation passed.
- Clean npm CLI audit, high severity, official registry, finite 20-second timeout and zero retries: 0 vulnerabilities.
- `node scripts/sync-doc-examples.mjs --check`: passed.
- `git diff --check`: passed.

After pushing this follow-up, exact-commit backend/frontend-sdk/security/images jobs must pass before deploying it. Final public gzip verification must go through https://metertrail.com, not only localhost. Browser visual acceptance remains incomplete; no Lighthouse or latency guarantee is implied.
