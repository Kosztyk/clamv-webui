# Maven `verify` regression repairs — 969-test run (2026-11-13)

## Starting point

The user-supplied Docker build log for the complete **ClamAV Web UI v2.1.0beta2** source showed that Java production and test code **compiled**, but Surefire reported **969 tests run, six assertions failed, five tests errored, zero skipped**. These repairs are based on that actual log; the Maven suite has **not** been rerun in this packaging environment, where Maven and downloaded dependencies are unavailable.

## Repairs

| Source / test | Failure addressed | Correction |
|---|---|---|
| `ConfigurationConsumerWiringTest.java` | Flyway test expected schema version `4` | Assert current bundled version `7`, retaining all V1–V7 migrations unchanged |
| `ConfigurationConsumerWiringTest.java` | Expected old upstream error text `Cannot reach` | Expect the new sanitized, stable `Cannot read the configured external REST API response` message; **do not** restore leakage-prone error text |
| `ConfigurationConsumerWiringTest.java` | Mock repository returned null for new API v1 projection query | Stub `findFilteredSummaries(...)` with an empty `Page` rather than the retired `findAll(Specification, Pageable)` path |
| `ConfigurationConsumerWiringTest.java` | Test attempted to save custom upload/quarantine paths before creation | Create the directories as required by the actual Configuration Center safety policy |
| `ConfigurationAllFieldsTest.java` | Generic `path` branch swallowed specialized private multipart-path fixture | Move the specialized branch before generic `path` matching and apply private `0700` permissions |
| `ConfigurationAllFieldsTest.java` | Test tried to lower total multipart request limit below 2047MB file limit | Use 2MB file limit in that test case so the 8MB request limit has valid headroom; keep production validation strict |
| `Issue30MultipartConfigurationCenterTest.java` / `ConfigurationCatalog.java` | Symlink rejection produced generic “directory must exist” before symlink check | Inspect symlink path components **before** checking directory presence, maintaining the existing security block |
| `SessionLifecycleHttpIntegrationTest.java` | Two tests submitted HTTP cookies while the production default now marks `JSESSIONID` Secure | Explicitly set `server.servlet.session.cookie.secure=false` **for this HTTP-only test class**; the production default stays secure |
| `RestMonitorIntegrationTest.java` | Mock external client reported a maximum of zero history jobs, so page 2 was empty | Stub a realistic positive `reportMaxJobs()` value of 500, retaining the response cap and snapshot cache |
| `UploadBatchPersistenceTest.java` / `ScanJobService.java` | Standard servlet multipart test uses `MockPart.write()` which throws `UnsupportedOperationException` | When `transferTo(File)` is unsupported and no destination has been created, fall back to private streaming copy and SHA-256. A successful Tomcat file move still uses the fast path; other copy, checksum, rollback and file-permission policies are unchanged. |

## Source preservation

Started from `clamav-webui-v2.1.0beta2-issues25-35-metrics-test-compile-fixed-full-source.zip` (1,190 files). Six existing files were edited and this document added. No previous files removed; no Flyway SQL, application defaults, auth security settings, API DTO contracts or issue #35 counters changed.

## Validation performed here

Passed: all current source/static checks, policy/observer Java 17 offline test, endpoint-service Java 17 offline test, API v1 DTO Java 17 offline test, JSON/localization verification, Flyway V5/V6/V7 SQLite SQL smoke tests, archive source-security audit and ZIP CRC check.

**Not executed here**: `mvn verify`, H2 integration tests, multi-DB Testcontainers tests, and live ClamAV/Tomcat integration; these require a Maven build environment. The old build log's 11 failures are addressed, **not represented as confirmed passing**.

## Recommended build check

```bash
# Run in a clean extraction containing pom.xml and Dockerfile.
docker build --progress=plain --no-cache -t clamav-web-client:v2.1.0beta2-verify .
```

If any test still fails, retain `target/surefire-reports/` or rerun with `mvn -B verify` in a Maven-capable environment and share the **failure summary and first `Caused by:`** for the failing tests. Don't disable tests (`-DskipTests`) to build a production image.
