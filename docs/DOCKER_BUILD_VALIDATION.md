# Container build correction validation

Validated on 30 October 2026 (Europe/Bucharest), against the supplied failed
Docker build log. That build reported six errors: four static-mocking errors in
OIDC tests and two missing monitoring-asset errors.

## Changes

- Docker's build stage copies `dashboards/` before `mvn verify`.
- Test resources select `mock-maker-inline` explicitly.
- The managed `mockito-core` dependency is explicitly test-scoped; Maven's
  dependency plugin resolves its agent path for Surefire and Failsafe. Mockito
  starts as a test JVM agent, without relying on dynamic self-attachment or
  hand-written paths into a particular host's Maven cache.
- Verification remains enabled in the Dockerfile.

## Results

| Check | Result |
|---|---|
| Reported failing test classes | Seven cases passed; zero failures/errors |
| Full Java 17 `clean verify` in the source tree | 211 cases: **206 passed, 5 skipped**; zero failures/errors; executable JAR packaged |
| Full Java 21 `clean verify` with Docker's source inputs | Same **206 passed, 5 skipped**; zero failures/errors; executable JAR packaged |
| Java 21 runtime | Eclipse Temurin **21.0.12.1+1**, matching the reported build's JDK version |
| Manual `argLine` / agent options | None; both verification commands use the new POM configuration |
| Production JAR inspection | No Mockito dependency, test mock-maker resource or Byte Buddy agent packaged |
| Dockerfile build inputs | `pom.xml`, `src/` and `dashboards/`; dashboard copy precedes verification |

The Java 21 check ran in an isolated directory populated only with the files
copied by the Dockerfile. This verifies that the tests no longer depend on
uncopied files from the full working tree. The JDK archive's SHA-256 was verified
against the official Eclipse Temurin release checksum before use.

Five existing AF_UNIX tests are skipped because this execution environment
prohibits Unix socket creation. They remain enabled on hosts that support Unix
sockets. The host's supplied build ran those tests; they were not among its six
reported errors.

A Docker engine is not available in this verification environment. The complete
OCI image build, runtime package installation and container startup were not
rerun here. The reported failure was in Maven verification, which now passes
under Java 21 using the corrected Docker build inputs. Runtime Docker stages,
application logic, migrations, identity implementation, CLI/MCP binaries and
monitoring assets are otherwise unchanged.

Use the build and upgrade commands in `DOCKER_BUILD.md`. Existing external
PostgreSQL settings and `conf`/`data` volumes should be retained.
