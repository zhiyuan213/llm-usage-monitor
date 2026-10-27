# Scenario-based integration documentation — 2026-10-27

## Change
- REST: two main scenarios plus optional separate score; responsive branch diagram; independent POST bodies with fresh IDs/time and shared traceId. Changing main scenario regenerates identity to avoid revision conflicts. Score keeps the earlier target identity.
- SDK: Node/Python/Java each have complete single/multiple/score examples (nine sources), sourced from the same canonical event fixtures as REST. Explains installation, environment, prior score target, receipt semantics and in-memory automatic batching.
- No ingestion/backend/SDK behavior changed. Existing uncommitted mail/template changes excluded. Corrected stale first-project-key verification copy in quickstart/security.

## Pre-commit evidence
- npm --prefix frontend test: PASS 42 tests /15 files.
- npm --prefix frontend run build: PASS; 11 static public pages.
- npm audit --audit-level=high (clean bundled npm, official registry, no retries, bounded timeout): PASS, 0 vulnerabilities.
- node scripts/sync-doc-examples.mjs --check and node scripts/sync-scenario-examples.mjs --check: PASS.
- node --test sdk/node/test.mjs scripts/sync-doc-examples.test.mjs: PASS 10 tests.
- python -m unittest discover -s sdk/python -p test_*.py: PASS 6 tests.
- mvn -B -gs .local/maven-ingest-settings.xml -Dmaven.repo.local=D:/code/llm-usage-monitor/.local/insights-maven-cache -f sdk/java/pom.xml verify dependency:build-classpath -Dmdep.outputFile=target/scenario-classpath.txt: PASS 4 tests.
- node scripts/check-scenario-examples.mjs: PASS all nine actual SDK scripts against local capture HTTP server; batch sizes [2], [1,1,2], [1]; identity/fields/score targets checked, no real data or API key used. This caught and fixed placeholder-based spanId expansion before commit.
- Local browser: REST scenario switching/diagram and SDK Java score/language switching verified. Copy button acknowledges success; browser clipboard bridge did not expose matching text, so clipboard content not independently confirmed. Responsive scoped CSS inspected; no live 390px viewport test available from browser tool.
- Full backend verify not run locally: documentation/UI only, no backend/report implementation changed. All four exact-SHA CI jobs are required before deployment.
