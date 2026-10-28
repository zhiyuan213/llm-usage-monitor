# Java objects and HTTP 202 upload receipts

## Changes

- Java tutorial is a runnable Maven / Java 17 project. Records define Agent, BatchRequest, Event, TraceData, ObservationData and Usage; Jackson serializes them. Required null usage values remain present. Java SDK scenarios also construct typed objects.
- POST /v1/events/batch returns 202 Accepted after committed acceptance in both normal and durable queued paths. Its pending receipt is unchanged. GET /v1/batches/{id} remains 200. Existing SDK support for both 200 and 202 is retained for rolling upgrades.
- Public references, generated examples, tutorial clients and capture servers use 202. Go/PHP/Java examples no longer reject the new success status.

## Pre-commit local verification

- `mvn -B -f backend/pom.xml verify` in Maven 3.9 / JDK 17 Docker with Testcontainers: 204 tests, zero failures/errors/skips. Includes both HTTP acceptance paths, invalid inputs, quota and query contracts.
- `mvn -B -f sdk/java/pom.xml verify`: 4 tests passed, including actual HTTP 202 receipt, retry/auth recovery and explicit GET returning 200.
- `mvn -B -f examples/rest/java/pom.xml verify`: 1 serialization contract test passed; required null usage fields retained, optional event span omitted.
- `npm --prefix frontend test`: 20 files / 59 tests passed. `npm --prefix frontend run build`: production build and 11 static public pages succeeded.
- `node --test sdk/node/test.mjs scripts/sync-doc-examples.test.mjs`: 10 tests passed.
- `python -m unittest discover -s sdk/python -p 'test_*.py'`: 6 tests passed.
- `node scripts/check-scenario-examples.mjs`: all 12 published Node/Python/Java scenarios executed against an HTTP 202 capture server, including all required usage keys.
- `node scripts/check-tutorials.mjs` from frontend: all four exact published tutorial programs executed successfully against HTTP 202 / pending; Java uses the supplied Maven project.
- `node scripts/npm-audit.mjs` in Node 24 Docker: zero vulnerabilities.
- `node scripts/sync-doc-examples.mjs --check`, `node scripts/sync-scenario-examples.mjs --check`, `node scripts/write-sdk-id-guides.mjs --check`: passed.
- Browser: Java Maven configuration, record definitions, run command and HTTP 202 guidance rendered; source copy button reports copied. Desktop 1920 and narrow 390 verified, narrow document width 375 equals scroll width. The browser clipboard API returned empty text, so clipboard content itself was not asserted. Saved preview `.local/java-typed-desktop.png`.

Windows runners use JDK 17 with `-Djdk.net.unixdomain.tmpdir=D:/devtools/tmp` and an official Maven 3.9 distribution. No live keys, payments or production units were used by these tests.

This is local verification only. Deployment requires all four required GitHub Actions jobs to succeed for the exact pushed commit.
