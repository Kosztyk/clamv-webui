# Phase 4 validation report — ClamAV Web UI v2.0.0

## Source baseline

Built on `clamv-webui-v2.0.0-phase3.1-ldap-viewer-navigation-rolefix03-full-source.zip` (the latest full source provided before the Phase 4 request). Existing source, authentication, user data and migrations preserved.

## Round 1: Structural correctness (passed)

- `pom.xml` parses as XML and includes `micrometer-registry-prometheus`.
- All dashboard Grafana JSON parses. Prometheus scrape and alert YAML parse.
- New Administration → Configuration route and tab exist; read-only only.
- Security request matcher guards other actuator paths; `/actuator/prometheus` has a separate fail-closed bearer filter (no configured token = HTTP 403 by design).
- No database migration changes; app version remains `2.0.0`.

## Round 2: Java implementation checks (partial, passed for isolated classes)

- Java 21 compiler successfully compiled the config observer against stub Spring annotations.
- Runtime observer test passed: quoted values, include resolution, repeated directives, secret-key redaction, discovery disabled by default.
- Java 21 compiler successfully compiled `ScanMetrics` and `ClamdMetricsCollector` against representative Micrometer/Spring API stubs. This caught and corrected a metrics package import error.
- Added JUnit tests: config parser, metrics failure labels/timer, Prometheus secret gate, duplicate completion at common boundary.
- **Not executed:** Actual Maven compilation or JUnit suite against the real libraries. No Maven dependency cache and no network access in this runtime. Compile-check stubs do not establish actual dependency compatibility.

## Round 3: Browser and deployment artifact checks (passed for static fixture)

- Chromium with the actual CSS and sidebar markup at 1440 px confirmed: Dashboard/Endpoint Health/ClamdTop same horizontal alignment and sizes; API Reports below API Scan; language dropdown not clipped by paint containment.
- Configuration HTML has valid parseable HTML structure and correct `fragments/shell` integration.
- Deployment examples use token-gated management metrics and restrict host port 9091 to localhost, or recommend an isolated internal Docker network.
- The complete archive is verified with ZIP CRC and source file inventory comparison.
- **Not executed:** Real Spring/Thymeleaf render, WebSocket ClamdTop run, LDAP/OIDC login, PostgreSQL integration, actual Prometheus scrape and Grafana import on your host.

## Deployment gate / required host validation

**Do not describe this as production-tested yet.** Before deploying to production, run:

```bash
mvn -B -ntp verify
python3 scripts/phase4-static-check.py
docker compose config --quiet
docker compose build --no-cache clamav-web-client
```

Only after a successful build, back up PostgreSQL and conf/data, deploy to the test container and verify the `/actuator/prometheus` HTTP 403 without a token and 200 with the correct token; scan EICAR and inspect exactly one increment in completion/detection counters; disconnect ClamD and confirm `available=0` and unknown numeric stats; view read-only config and test file edits; test LDAP administrator login, OIDC if enabled, full UI navigation, and the existing ClamdTop terminal.

The Phase 0 GitHub CI workflow has been extended to run static artifact validation and a fail-closed metrics smoke check after Maven and Docker build.
