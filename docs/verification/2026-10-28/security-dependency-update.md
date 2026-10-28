# Dependency security update for the account/prices release

2026-10-28. The preceding implementation commit c6c6e98 remains undeployed because its exact CI run failed the dependency security gate.

Changes: Boot 4.0.9 managed Spring 7.0.10, Tomcat 11.0.27, Netty 4.2.19 and Jackson2 2.21.7; retain Java17 and existing Jackson2 wire types using the documented compatibility module. Flyway now uses its dedicated starter. Adapt JDBC/security/test packages and Mockito bean overrides. Explicit Jackson2 converters are configured for manually constructed GitHub, Paddle and Creem clients; the application converter preference alone does not customize these builders.

Verification:
- Full `mvn -B -f backend/pom.xml verify` in Docker Maven/Java17: BUILD SUCCESS, 220 tests, zero failures/errors/skips (.local/boot4-final-verify.log).
- Trivy 0.70.0 backend POM dependency scan, HIGH/CRITICAL, ignore-unfixed, exit-code1, excludes target artifacts: exit 0, zero findings (.local/boot4-security.log). No vulnerability exceptions added.
- Existing frontend changes were verified with 61 tests, production build, 114-package advisory check (zero), and all three example sync checks; no frontend sources changed in this follow-up.
- A new real HTTP echo regression verifies outbound JsonNode payload and response compatibility. Existing GitHub identity conflict/registration tests use the real HTTP converter with the mock transport. The old static create() test stub was updated to the shared builder factory; assertions remain unchanged.
- The first migration attempt exposed the missing dedicated Flyway module; initial tests caught empty schema before any deployment. A subsequent test caught manually built clients selecting Jackson3; fixed explicitly rather than changing response expectations.

Exact follow-up CI and immutable-image verification remain required before production activation.
