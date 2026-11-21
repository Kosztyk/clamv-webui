# Issue #37 — validation status (2026-11-13)

## Performed offline

- `python3 scripts/check-issue37-maintenance.py` — **15/15 PASS**: pinned Docker bases, clamdtop extraction/no scanner, liveness route and Docker healthcheck, Actuator/Prometheus security retained, unused dependencies removed, Java 21, JVM memory flags, all four JDBC drivers preserved and CI hooks.
- `python3 scripts/check-issue36-h2-source.py` — **10/10 PASS**: #36 safe H2 JDBC URL, historical URL reset, all Compose 60-second stop windows, regression tests and smoke path still present.
- `python3 scripts/check-safe-scanning-source.py` — **PASS**: scanner isolation, upload defaults, configuration security.
- `python3 tools/audit-source-package.py .` — **PASS**: source audit; no runtime data/credential paths identified.
- XML parse (`pom.xml`), YAML parse (`.github/dependabot.yml` and `.github/workflows/issue37-container-maintenance.yml`), shell syntax (all new .sh and `docker-entrypoint.sh`), Python syntax (new offline check) — **PASS**.
- ZIP integrity and byte count verified after packaging.

## Not run on this machine

- **Maven compile/integration tests**, because Maven is unavailable locally. Maven verification remains in the pinned Docker build and the new CI workflow.
- **Docker build/`docker inspect` health / real native clamdtop**, because Docker is unavailable locally.
- **Measured image-size difference** vs. the issue #36 image: cannot be calculated honestly without building both images. A PR workflow is included to build both candidates and produce real byte/MiB/% values; `scripts/issue37-verify-and-compare.sh` performs the same verification on a Docker-enabled host.

## Acceptance criteria to confirm before closing #37

```sh
mvn -B -Dtest=Issue37LivenessSecurityTest test
docker build --pull -t clamav-web-client:issue37 .
./scripts/issue37-smoke.sh clamav-web-client:issue37
# After your Compose deployment uses the candidate image:
docker inspect clamav-web-client --format '{{.State.Health.Status}}'
# If the previous image is built/tagged on the same Docker host:
./scripts/issue37-verify-and-compare.sh clamav-web-client:issue36-before clamav-web-client:issue37 clamav-web-client
```

Issue #37 should not be marked verified solely from offline source tests.
