# Request explorer and pagination verification — 2026-10-27

Scope: replace overview mixed activity rows with request cards and associated details; paginate public demo; server-side slow-request filter shared by report and export. No payment or ingestion contract changes.

Before commit:
- `mvn -B -gs .local/maven-ingest-settings.xml -Dmaven.repo.local=D:/code/llm-usage-monitor/.local/insights-maven-cache -f backend/pom.xml verify`: PASS (BUILD SUCCESS, 20:59:35 +08:00). Includes IngestIntegrationTest export headers/all rows/pagination/PDF/ownership and new slow-filter/foreign-project checks.
- `npm --prefix frontend test -- --run`: PASS, 47 tests / 16 files.
- `npm --prefix frontend run build`: PASS; production JS/CSS and 11 public pages generated.
- clean npm CLI audit against registry.npmjs.org, audit-level high: PASS, 0 vulnerabilities.
- `node scripts/sync-doc-examples.mjs --check`: PASS.
- `node scripts/sync-scenario-examples.mjs --check`: PASS.
- `node scripts/write-sdk-id-guides.mjs --check`: PASS.
- `git diff --check`: PASS (line-ending notices only).

Browser acceptance on localhost:
- Demo defaults to 5 rows: 6 records span 2 pages; second page selects its request and updates details; slow filter resets to page 1 with 2 results.
- Authenticated explorer component exercised using an isolated synthetic fetch fixture (36 records): 25-row default, change to 10 rows, next page, selected request 11, slow filter total 18 with page reset.
- Desktop layout and 390px iframe checked; narrow layout stacks panels and wraps controls without horizontal overflow. Detail summary rows aligned after scoped CSS correction.
- Unknown cost/usage remains unknown; generation-only summary avoids Trace/Score double counting; width clamp and page slices covered by unit tests.

Not yet production evidence: CI and deployment must be checked for the exact implementation commit after push. Temporary fixture HTML removed before commit.
