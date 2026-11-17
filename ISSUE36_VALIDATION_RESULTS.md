# Issue #36 validation record

Source baseline: uploaded `clamav-beta2-issue36-full(1).zip` (v2.1.0beta2).

## Completed offline checks

- `python3 scripts/check-issue36-h2-source.py`: **PASS** (10 configuration/source contracts, including legacy -1 live-database reset).
- `bash -n scripts/verify-issue36-h2-shutdown.sh`: **PASS** (shell syntax).
- `python3 -m py_compile scripts/check-issue36-h2-source.py`: **PASS** (Python syntax).
- PyYAML parse of all 5 root Compose files: **PASS**.
- Four standalone Web UI Compose files now include `stop_grace_period: 60s`; the fifth is a ClamD-only overlay and needs no Web UI stop grace period.

## Not executed in this environment

- `mvn -B -Dtest=Issue36H2ShutdownTest test` and `mvn -B verify`: Maven and the local H2/Hikari dependency cache are absent; external package repository DNS is unavailable.
- `docker compose build clamav-web-client` and the real Docker stop/restart acceptance test: Docker CLI/daemon unavailable.

## Required runtime acceptance checks

On a Docker-enabled host with Maven/download access, run:

```bash
mvn -B -Dtest=Issue36H2ShutdownTest test
mvn -B verify
docker compose build clamav-web-client
bash scripts/verify-issue36-h2-shutdown.sh clamav-web-client:2.1.0beta2-local
```

The smoke script must print **PASS** for both post-shutdown lock checks and for the full issue #36 test. The second startup must read the same test database without corruption/recovery errors. A normal lock file while H2 is running is expected.

Do not mark the runtime criterion confirmed before running the real smoke test; the source checks and included test scripts are not a substitute for running it.
