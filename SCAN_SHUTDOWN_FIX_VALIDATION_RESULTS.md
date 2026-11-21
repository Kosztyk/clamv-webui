# Scan worker shutdown lifecycle — validation report

## Checks executed in the offline workspace

- Java 21 compiler AST parse for all **302 Java sources** (including new tests): **PASS**, zero syntax errors.
- `scripts/check-issue58-hardening.py`: **PASS**, including new lifecycle/cancellation/sweeper regression guard.
- `scripts/test-issue58-hardening.py`: **PASS**.
- `scripts/check-issue36-h2-source.py`: **PASS** (H2 shutdown safety source invariants preserved).
- `scripts/check-issue37-maintenance.py`: **PASS** (Docker stop grace, image, healthcheck invariants preserved).
- Additional checks successfully completed by the offline sweep included source, database, endpoint, security, audit, API, i18n, CI and release static checks. The sweep exceeded its execution budget before all 50 scripts finished; incomplete tests are not claimed to have passed.

## Checks requiring CI or deployment runtime

- **Not executed here:** Maven/JUnit/Mockito concurrency tests, Spring context lifecycle, JaCoCo, Docker SIGTERM with a live ClamAV connection, and full H2/PostgreSQL shutdown/restart recovery tests. Maven and dependencies are not installed and external Maven Central DNS is unavailable in this runtime.
- **Required before release:** `mvn -B -ntp clean verify` on JDK 21/25, CI quality gate, and a Docker SIGTERM/EICAR test with deliberately long scans on the actual DB backend. Verify clean Hikari shutdown and no terminal `ERROR` for cancellation.

## Operational contract

- Stop dispatch at Spring SmartLifecycle phase `Integer.MAX_VALUE-2048` before bean destruction.
- `QUEUED` jobs stay `QUEUED` even when their local Future is removed.
- Active scans may commit normally during the grace period; once forced cancellation starts, worker transitions must avoid terminal verdict writes.
- Interrupted `RUNNING` jobs remain `RUNNING` until restarted, at which point the existing startup bulk update makes them `QUEUED` and bounded reconciliation dispatches them again.
- The default budgets are **20+5 seconds**, below the default 30-second Spring shutdown phase limit and supplied 60-second Compose `stop_grace_period`.
- Remote ClamAV operations may block without honoring interruption. Daemon threads are only the final JVM-exit safeguard; if they survive the 5-second final wait, do not claim all workers stopped or that every scan completed.
