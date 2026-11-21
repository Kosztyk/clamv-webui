# Issue #37: Docker image / dependencies / liveness

Baseline: the full-source archive for #36 (`clamav-webui-v2.1.0beta2-issue36-persistent-delay-reset-full-source.zip`). All previous #13-#36 remediations are preserved.

## Modified
- `Dockerfile` — pinned Maven/Temurin image digest; separate Debian-package extraction stage copying only `/usr/bin/clamdtop` and its copyright notice; minimal runtime libraries (conditional libclamav), curl liveness probe; no clamd/freshclam executables, immutable probe/entrypoint, Java 21 runtime.
- `pom.xml` — Java 21; remove unused `spring-boot-h2console`, `spring-boot-starter-mail`.
- `src/main/java/info/trizub/clamav/webclient/config/SecurityConfig.java` — anonymous **GET-only** `/actuator/health/liveness`; remove H2 console permissions, H2 CSRF exception, same-origin frames allowance (returns to Spring Security default DENY).
- `src/main/resources/application.properties` — enable Actuator liveness probe; remove H2 console configuration.
- `docker-entrypoint.sh` — `-XX:MaxRAMPercentage=${JAVA_MAX_RAM_PERCENTAGE:-70.0}` and `-XX:+ExitOnOutOfMemoryError`.
- `README.md`, `docs/releases/v2.1.0beta2.md` — instructions and compatibility.
- `src/main/java/info/trizub/clamav/webclient/web/NativeClamdtopWebSocket.java` — update diagnosis text for the extracted-binary deployment, no longer instruct operators to install the daemon package. The obsolete unused `NativeClamdtopWebSocketHandler.java` was subsequently removed by issue #38.

## Added
- `scripts/issue37-healthcheck.sh` — credentials-free loopback-only probe; honors `MANAGEMENT_SERVER_PORT` and `SERVER_PORT`.
- `scripts/issue37-smoke.sh` — isolated disposable Docker startup/health/authentication/non-root/H2-stop test.
- `scripts/issue37-verify-and-compare.sh` — **actual** old/new image sizes, current container healthy status, clamdtop dependencies check.
- `scripts/check-issue37-maintenance.py` — offline source checks.
- `src/test/java/info/trizub/clamav/webclient/security/Issue37LivenessSecurityTest.java` — anonymous liveness + protected actuator + DENY frames + no H2 console.
- `.github/dependabot.yml` — weekly Docker/Maven/Actions refresh PRs, including immutable Dockerfile digest changes.
- `.github/workflows/issue37-container-maintenance.yml` — on PR, build baseline and candidate, run Docker HEALTHCHECK smoke and report actual image-byte change to the Actions summary.
- `docs/ISSUE37_CONTAINER_MAINTENANCE.md` — deployment/test/security notes.

## Removed
- `src/main/java/info/trizub/clamav/webclient/config/SecurityConfig.java.bak` — obsolete source backup containing the now-removed H2 exemptions.

## Deliberate compatibility decisions
- Leave all four supported database engines and their JDBC/Flyway drivers in default JAR. Existing PostgreSQL/MySQL/MariaDB deployments must **not** fail after an image update.
- Preserve `/actuator/prometheus` and the token validation filter unchanged. Moving Actuator to a new port *by default* would break configured NGINX/Prometheus deployments; optional dedicated `MANAGEMENT_SERVER_PORT` is supported but must be reviewed for network access controls.
- `Dockerfile.clamd` was already digest-pinned; external `CLAMAV_BASE_IMAGE` remains a required explicit pin for the scanner service. Scanner container is a separate official ClamAV image; this work reduces only the Web UI image.

## What cannot be claimed before runtime tests
- Running Docker container `healthy` state, compilation success, precise old/new `docker image inspect .Size` difference or actual runtime bytes saved. Docker/Maven are unavailable in this environment; run the commands in the docs before marking #37 fixed on GitHub.
