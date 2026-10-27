# Key verification dialog — 2026-10-27

- Removed the inline key-name field from the keys toolbar. Creation opens the verification dialog with an editable name; sending a code binds the challenge to that name and locks editing while sending or after success.
- Revocation shows the selected key name and effect in plain language, without the internal action or target IDs. Existing verification request/action and revocation endpoints remain unchanged.
- Scoped dialog input sizing uses border-box and zero minimum width for narrow layouts. Live desktop/narrow-screen browser interaction was not performed; no real keys created or revoked.

Before commit:
- `npm --prefix frontend test`: passed, 40 tests / 14 files.
- `npm --prefix frontend run build`: passed, production assets and 10 static public pages.
- `node .local/clean-npm/package/bin/npm-cli.js --prefix frontend audit --audit-level=high --json --registry=https://registry.npmjs.org --fetch-retries=0 --fetch-timeout=20000`: passed, zero vulnerabilities.
- `node scripts/sync-doc-examples.mjs --check`: passed.
- Backend and SDK contracts unchanged; full exact-commit CI is required before deployment.
