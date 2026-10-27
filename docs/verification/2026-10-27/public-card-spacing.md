# Public card spacing correction

2026-10-27. User screenshot showed the homepage plan summary text touching its border and ungrouped links.

Scope: give the plan summary explicit 32px desktop / 20px narrow padding, separate copy and actions, use a primary comparison action and secondary reference link. Align tutorial-card entry links, remove excess heading top margin and center the case-study demo action. Preserve prices, claims, destinations, consent and integrations. Reviewed pricing/security containers in source; their existing padding is retained.

Before commit:
- `npm test`: 36 tests / 13 files passed.
- `npm run build`: passed, including TypeScript and public static HTML generation.
- Clean npm CLI official-registry audit (high severity, 20s fetch timeout, no retries): 0 vulnerabilities.
- `node scripts/sync-doc-examples.mjs --check`: passed.
- `git diff --check`: passed.

The screenshot is the visual problem evidence. Browser automation has previously been blocked by inability to determine the current browser URL. This change has not completed browser visual acceptance; source review/build/tests must not be described as desktop/mobile visual verification. All four exact-commit CI jobs remain required before deployment.
