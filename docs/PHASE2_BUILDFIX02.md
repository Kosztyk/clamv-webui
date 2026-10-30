# Phase 2 build fix 02 — test failures from the first Docker build

The user-supplied `mvn -B -q verify` output shows 59 tests, 0 failures and 2 errors:

1. `Phase2H2MigrationTest.operatorBaselineH2PreservesAllRows` fails because the test helper split the vendor V1 SQL on every semicolon. The V1 header contains SQL comments, including a semicolon, and H2 receives fragments beginning with `new databases only.`. The fixture now uses Spring JDBC `ScriptUtils.executeSqlScript` with a `ClassPathResource`, which handles SQL comments and statement delimiters correctly.
2. `ScanJobAtomicClaimTest.aQueuedJobCanBeClaimedExactlyOnce` fails during JPA test-slice startup because the application has an explicit `@ComponentScan` that bypasses Spring Boot's `TypeExcludeFilter`. That scan brings in `ScanJobService` and its `ObjectMapper` dependency, which are intentionally absent in a JPA slice. Removed the redundant `@ComponentScan`: `@SpringBootApplication` already scans the application package and additionally retains Boot test-slice filters. A regression check asserts that the JPA slice does not instantiate the web API controller or scan service.

No production Flyway V1 or V2 migration code, database schema, default database location, Docker configuration, API functionality, UI, or static assets changed. All 703 files of the prior full-source ZIP are retained, with one additional document.

## Verification

- Run the normal Dockerfile build: `docker compose build --no-cache clamav-web-client`; its build stage invokes `mvn -B -q verify`.
- Alternatively run `mvn -B verify` on a Java/Maven-enabled host.
- Execute the optional multi-vendor suite with `mvn -B -Pdb-integration verify` on a Docker-enabled host.
- Back up existing H2 database and `conf/` before attempting the Phase 2 migration. The setting `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` is **not** needed for a fresh database, and should only be enabled for a validated, backed-up legacy schema.

The provided build log also includes a warning that the running older container has legacy `admin/admin` credentials. Rotate them via Admin > Users. A failed Docker build does not replace the older running container.

Maven and Docker commands could not be executed in the artifact-building environment; host-side build/test results remain required for acceptance.
