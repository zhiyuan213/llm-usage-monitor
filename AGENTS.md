# Repository verification

- Read `docs/ci-incidents.md` when diagnosing CI failures. Record new root causes, the fix and meaningful prevention checks there.
- Keep real security findings blocking. Retry only transient service/network failures with finite attempts and timeouts; never turn unavailable security evidence into a successful scan.
- Before reporting a pushed fix as verified, inspect GitHub Actions for that exact commit and confirm all required jobs, including SDK/security/images, pass. If checks are still pending or fail, report that state accurately and continue investigating.
- Local frontend tests/build alone do not cover the full `frontend-sdk` job. Include dependency audit and generated-example synchronization when relevant.
