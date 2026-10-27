# Request explorer alignment and navigation

Frontend-only change. Overview exports removed; Traces exports retained. Demo uses the same five-metric order and request/detail styling. Request links read View full request / 查看完整请求. Detail Back uses the originating overview/traces history entry, with a Traces fallback for direct entry.

Local verification:
- npm --prefix frontend test: 16 files, 47 tests passed.
- npm --prefix frontend run build: passed (vue-tsc, Vite, 11 public pages).
- npm audit --audit-level=high using clean local npm CLI: 0 vulnerabilities.
- node scripts/sync-doc-examples.mjs --check: passed.
- node scripts/sync-scenario-examples.mjs --check: passed.
- node scripts/write-sdk-id-guides.mjs --check: passed.
- git diff --check: passed.
- Local browser fixture: overview -> full request -> Back returns overview; traces -> full request -> Back returns traces. Overview has no export links; Traces retains both. Demo five metrics and pagination present.
- Responsive browser inspection: single-column explorer at narrow viewport, no document horizontal overflow. IAB screenshot scaling makes nominal viewport dimensions unreliable; this is not a claim of pixel-perfect 390px verification.

CI and deployment are checked separately after commit; these local checks do not replace all four required GitHub jobs.
