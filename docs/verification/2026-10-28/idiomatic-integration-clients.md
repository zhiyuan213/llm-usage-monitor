# Reusable Java, Node.js and Python integration examples

Scope is the existing Java, Node.js and Python tutorials plus the REST/cURL companion. Go, C# and PHP are unchanged.

- Java: independent JavaBean DTOs in model/, private fields and getters/setters, reusable MeterTrailClient, typed BatchReceipt. Send.java only constructs objects and calls sendBatch. No positional record constructors or handwritten JSON. Every supporting source file is published beside the Maven configuration.
- Python: snake_case dataclasses, explicit camelCase protocol conversion, keyword arguments, reusable client and typed receipt. Unknown Usage members remain explicit null. Importing the module does not send requests.
- Node.js: native request objects, reusable async client, named factory and main guard. Importing the module does not send requests.
- cURL is now standalone with complete schema/agent/event/usage fields and preserved batch.json for retry.

Pre-commit verification:

- `mvn -B -f examples/rest/java/pom.xml verify`: 1 test, zero failures/errors/skips; JavaBean serialization retains all required unknown usage fields.
- Exact published tutorial runner: all four tutorials compile/run against a local capture server and accept HTTP 202; all four reject HTTP 400. Node/Python import-only checks and Python unknown-usage serialization passed. Java copies every published companion file into the temporary Maven project before running.
- Frontend Vitest: 20 files / 59 tests passed. vue-tsc, Vite production build and static public-page generator passed (11 pages). Vite reports a bundle-size advisory after embedding the additional DTO sources; build succeeded.
- Three generated-example checks and CRLF/LF generator regression passed.
- Official npm registry bulk advisory audit of all 114 distinct locked package names: HTTP 200, zero advisories. Local system npm is broken and local Docker is stopped; no local npm CLI success is claimed. CI must run its normal npm audit before deployment.
- Browser: Java main setter calls, separate JavaBean definitions and reusable client visible; copy feedback works. Desktop inspected, narrow document client/scroll width both 300px. IAB preview connection was unavailable, so a temporary Chrome tab was used and closed after verification.

Backend production code and SDK contracts are unchanged. Deployment remains gated on all four required CI jobs for this exact commit.
