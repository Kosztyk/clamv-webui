# Issue #37 — minimal Web UI runtime, liveness and dependency hygiene

This change follows the #36 full-source archive; does not modify scan mode, the external ClamD image, database schema, Configuration Center precedence, or existing Prometheus token protection.

## What changed, and why

- A dedicated build stage compiles upstream `clamdtop` against the packaged libxml2 ABI. The final image carries the ClamAV GPL notice. The runtime installs `clamav-libs`, curses and terminfo, the `util-linux-misc` PTY helper, and `curl`. ClamD and freshclam stay in the separate scanner service. Build and smoke checks start `clamdtop` to verify dynamic linkage.
- The Maven stage uses Ubuntu Noble and the Java 21 runtime uses Alpine 3.24. Both bases are pinned to multi-platform digests for AMD64 and ARM64. Dependabot proposes digest updates for review.
- Until Alpine 3.24 packages the `CVE-2026-119602` fix, the build compiles the upstream `libmspack` 0.12alpha release, runs its tests, and installs it as a signed APK. The package recipe verifies the source archive against the upstream SHA-256. The runtime smoke test checks the installed version and starts ClamdTop. [Upstream source and release](https://www.cabextract.org.uk/libmspack/) · [Release announcement](https://seclists.org/oss-sec/2026/q4/686)
- Fontconfig uses its libxml2 backend in the signed runtime APK. This preserves Java font discovery and removes Expat from the image while `CVE-2025-66382` is open. The build and smoke test check for Expat, and the smoke test resolves the installed DejaVu Sans font. [Upstream CVE tracker](https://github.com/libexpat/libexpat/issues/1076)
- The runtime uses libxml2 2.15.4 and Alpine’s signed OpenSSL 3.5.9-r1 packages. The build can reproduce those OpenSSL packages from the pinned Alpine recipe if the binary packages leave the repository. LLVM uses Alpine’s older libxml2 ABI while compiling ClamdTop. The runtime ships the ABI 16 package from libxml2 2.15.4. The build checks the installed OpenSSL, musl and libxml2 versions and verifies that the old Expat and libxml2 shared libraries are absent. The release image copies the updated root filesystem with Alpine’s installed-package database. BusyBox supplies the shell utilities. [OpenSSL security advisory](https://openssl-library.org/news/secadv/20261110.txt) · [Alpine OpenSSL backport](https://github.com/alpinelinux/aports/commit/5e6d18a5ba2eec060b34a671a43ee54a5e72714a)
- Executable JAR drops unused H2 web console and mail starter. File-backed H2 **remains supported**. Historical `/h2` CSRF exemption and `X-Frame-Options: SAMEORIGIN` exception are removed; frame handling returns to DENY.
- H2/PostgreSQL/MySQL/MariaDB JDBC and Flyway remain bundled deliberately; all are documented working deployment modes. Removing them by default would break deployments switching database engines without recompilation. Do not omit these silently.
- Maven now targets Java 21, matching the Docker runtime. Running the standalone JAR on Java 17 is no longer supported.
- JVM: `-XX:MaxRAMPercentage=${JAVA_MAX_RAM_PERCENTAGE:-70.0}` and `-XX:+ExitOnOutOfMemoryError`. Operators may set `JAVA_MAX_RAM_PERCENTAGE=60` for native-memory-heavy workloads and use `JAVA_TOOL_OPTIONS` for other JVM arguments.

## HEALTHCHECK, reverse proxies and security

Spring's standard `management.endpoint.health.probes.enabled=true` enables `/actuator/health/liveness` (minimal `{"status":"UP"}` while healthy). Only HTTP **GET** on this path is unauthenticated. `/actuator/health`, `/actuator/health/readiness`, `/actuator/info`, and all other Actuator endpoints remain ADMIN-only. `/actuator/prometheus` remains protected by the existing independent scrape-token filter; **do not expose it without a token**. Application API health endpoints do not change.

Docker's HTTP probe calls only container **loopback**, without credentials, every 30 seconds, with 6-second timeout, a 90-second start period and 3 retries. The response is liveness of the JVM/application, *not* a guarantee that an external ClamD endpoint/database/network is ready to scan. It does not create sessions, scrape metrics, or call ClamD.

The default port 8080 is maintained, which **does not change existing Prometheus URLs**. A separate management port is an **opt-in** isolation mode; set both:

```yaml
environment:
  MANAGEMENT_SERVER_PORT: "9091"
  MANAGEMENT_SERVER_ADDRESS: "127.0.0.1"
```

Do not publish `9091` to the host. When selecting this mode, existing Prometheus scrapes must be reviewed and may need a secure network route or explicit management listener address and firewall controls. `MANAGEMENT_SERVER_PORT` is automatically used by the image health check. `SERVER_PORT` is also supported for a custom primary listener. Do not set `SERVER_ADDRESS` to a non-loopback-only address if that would prevent local Docker liveness probes.

For Kubernetes, choose `httpGet: {path: /actuator/health/liveness, port: 8080}` on default configuration, or the internal management port if configured, **not** `/actuator/health` and **not** the authenticated `/api/v1/health/*`.

## Build / verify on a Docker + Maven machine

First, preserve your existing image as a local baseline (`clamav-web-client:issue36-before` below); or extract/build the previously supplied issue #36 archive under that tag. Build the candidate from this updated project tree:

```sh
mvn -B -Dtest=Issue37LivenessSecurityTest test
docker build --pull -t clamav-web-client:issue37 .
./scripts/issue37-smoke.sh clamav-web-client:issue37
```

Run your usual production Compose image **with the candidate tag** and verify the healthy status:

```sh
docker inspect clamav-web-client --format '{{.State.Health.Status}}'
docker inspect clamav-web-client --format '{{json .Config.Healthcheck}}'
```

Measure the precise image size *only after both images have been built on the same machine*:

```sh
./scripts/issue37-verify-and-compare.sh \
  clamav-web-client:issue36-before \
  clamav-web-client:issue37 \
  clamav-web-client
```

When container changes select the unified `docker-smoke` job, it builds the PR base and candidate, executes the health smoke and publishes the actual size difference in the PR job summary.

The script prints both actual `.Size` values (MiB), byte difference and percent change, and verifies the candidate is the image running in the named container; it **fails** if container health isn't `healthy`. Record its output in the #37 PR. It is not an estimate of savings. If it does not improve size, investigate `libclamav12` transitive dependencies and compare `docker history` and `docker image inspect`, rather than claim a reduction.

Offline source checks: `python3 scripts/check-issue37-maintenance.py`. Database H2 lock handling can be re-tested separately with the existing `scripts/verify-issue36-h2-shutdown.sh`.

## Operational notes

- Base image digests are intentionally immutable. Dependabot runs weekly, but digest updates are **reviewed**, not automatically deployed. Scanner image pin remains defined in `Dockerfile.clamd` / `docs/security/clamd-image-pin.json`; scanner Compose relies on the explicit `CLAMAV_BASE_IMAGE` setting.
- The final runtime stays UID/GID 10001 with owner-only upload permissions and keeps `script` and `clamdtop` for the native terminal. Existing `/app/data` and `/app/conf` Docker volumes and `stop_grace_period: 60s` remain unchanged.
- A health probe may report UP while remote ClamD is unavailable; use protected readiness/endpoint monitoring for scan dependency alerts. No unauthenticated database, filesystem, ClamD or settings data is exposed.
