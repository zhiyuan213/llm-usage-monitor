# Repository verification

- Before every implementation commit, run checks for the actual changed contracts, not only new targeted tests. Backend/report changes require `mvn -B -f backend/pom.xml verify`; report column changes must include `IngestIntegrationTest` export coverage (headers, all rows, pagination, PDF and ownership). Frontend changes require tests, production build, dependency audit and generated-example synchronization. Run SDK checks when their contracts change.
- Record the commands and results before committing. Do not label partial local checks as full verification. After every push, inspect the exact commit's backend, frontend-sdk, security and images jobs; pending, failed, skipped or unavailable checks block deployment and any claim of verified completion.
- Read `docs/ci-incidents.md` when diagnosing CI failures. Record new root causes, the fix and meaningful prevention checks there.
- Keep real security findings blocking. Retry only transient service/network failures with finite attempts and timeouts; never turn unavailable security evidence into a successful scan.
- Before reporting a pushed fix as verified, inspect GitHub Actions for that exact commit and confirm all required jobs, including SDK/security/images, pass. If checks are still pending or fail, report that state accurately and continue investigating.
- Local frontend tests/build alone do not cover the full `frontend-sdk` job. Include dependency audit and generated-example synchronization when relevant.

# UI design baseline

- Before adding or changing frontend pages, read `docs/ui-style-guide.md` and follow the current public website and console V2 design baseline.
- Reuse the existing components and scoped styles in `frontend/src/public-v2.css` and `frontend/src/console-v2.css`; preserve functionality unless the user explicitly requests behavior changes.
- Check desktop and narrow-screen layouts and the affected interactions. When the user explicitly changes the design baseline, update the style guide and implementation together.
