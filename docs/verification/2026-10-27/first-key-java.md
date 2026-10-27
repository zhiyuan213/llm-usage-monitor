# First project API key and Java tutorial — 2026-10-27

## Contracts
- GET `/api/projects/{project}/keys/creation-policy` returns `verificationRequired` for the authenticated owner. It includes revoked and soft-deleted key history.
- POST `/api/projects/{project}/keys` skips the email challenge only when no key has ever existed in the project. A project row lock serializes concurrent first creations. Login, ownership, CSRF, name validation, hashing, shown-once secret and audit remain unchanged. Later creation and revocation retain email verification.
- UI first creation shows the name and create button without email controls; a stale first-key decision falls back to verification when the server requires it.
- Added `/tutorials/java`, standalone JDK17 HTTP example, synchronized generated docs, static page/sitemap and analytics route. CI executes all four published tutorial examples, with JDK17 explicitly installed.

## Before commit verification
- Full backend `mvn -B -gs .local/maven-ingest-settings.xml -Dmaven.repo.local=D:/code/llm-usage-monitor/.local/insights-maven-cache -f backend/pom.xml verify`: PASS, 181 tests, zero failures/errors/skips. JAVA_HOME JDK17; JAVA_TOOL_OPTIONS `-Djdk.net.unixdomain.tmpdir=D:/devtools/tmp` avoids this Windows host's invalid AF_UNIX temp path. Initial default Maven mirror/cache attempt was stopped; a first verify failed at local JDK loopback initialization, then the full suite was rerun successfully with the temp directory override.
- Integration coverage includes concurrent first creation (one success only), CSRF/name validation, later creation, revoked/deleted history, unchanged revoke verification and anonymous access.
- Frontend tests: PASS 40 tests /14 files; production build PASS (11 static public pages).
- Dependency audit: PASS, zero vulnerabilities; generated-example sync and sync CRLF/LF test PASS.
- Exact published Node/Python/Java/REST tutorial execution against local capture server: PASS HTTP200/pending; expected two events and fields validated, no real keys/data sent.
- Font subset regenerated, 106456 bytes. Responsive CSS four/two/one columns and constrained modal retained. Live browser interaction not performed.
- Exact-commit backend/frontend-sdk/security/images CI required before deploy. Unrelated pending mail/template changes excluded from commit.
- Local web image check: PASS, including `/tutorials/java`, correct asset MIME/compression and private-route headers (`.local/first-key-java-image.log`).
