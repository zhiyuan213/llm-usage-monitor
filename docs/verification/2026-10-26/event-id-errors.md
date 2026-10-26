# Explicit eventId validation errors

- Missing/null/empty/blank: `EVENT_ID_REQUIRED`.
- Non-string: `INVALID_EVENT_ID_TYPE`.
- Not exactly 36 characters: `EVENT_ID_LENGTH_MUST_BE_36`.
- 36 characters but invalid hexadecimal UUID grouping: `INVALID_EVENT_ID_FORMAT`.
- Accepted IDs remain UUID strings with 8-4-4-4-12 groups, case insensitive. No deduplication, object identity or revision rules change. Existing batch records keep their historical error codes, documented as legacy.

Pre-commit verification, 2026-10-26:
- `mvn -B -f backend/pom.xml verify` in the existing Java 17 Maven container with Docker/Testcontainers: 173 tests, zero failures/errors/skips; BUILD SUCCESS. Ran from an isolated source snapshot including this patch, excluding unrelated pending mail changes.
- Added boundary coverage for missing/null/blank/non-string, 35/37/32-character IDs, 36-character invalid format, and valid uppercase/lowercase UUIDs.
- Integration regression checks all four codes reach stored batch errors with original event indices, valid neighbors are accepted, and unchanged retry deduplication/units remain correct.
- Frontend: `npm test` (29 tests) and `npm run build` pass; dependency audit using the existing clean npm CLI and official registry reports zero vulnerabilities; `node scripts/sync-doc-examples.mjs --check` passes.
- Browser checked the rendered error table and explicit 36-character field requirement; no public endpoint test data or credentials were used.

All four exact-commit CI jobs must pass before deployment.
