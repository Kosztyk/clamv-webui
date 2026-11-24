# Post-audit fixes: scan dispatch, Java quality, release upgrades

## 1. Bounded, durable scan dispatch

The scan worker now has a bounded `LinkedBlockingQueue` of **256 IDs by default**, configurable with `APP_SCAN_QUEUE_CAPACITY` / `app.scan.queue-capacity` (valid range 1–10000; restart required). The separate `app.concurrentScans` property still controls worker concurrency. Because accepted jobs are **committed to `scan_jobs` before local dispatch**, a full queue does not lose a job or cause an after-commit HTTP 500. Instead, it removes the rejected ID from the in-memory deduplication set, increments `clamav.scan.dispatch.deferred`, and leaves the record `QUEUED`. The existing scheduled `ScanJobService.reconcileQueued()` retries bounded DB batches (default 60 seconds) and workers atomically claim QUEUED → RUNNING. A one-worker/one-slot JUnit concurrency test exercises this behavior and startup capacity validation.

**Residual risk:** This limits only process memory, not the number of rows stored in the DB. Apply upload/API rate limits, disk quotas and retention. When scans are slower than intake, waiting time grows; the metric indicates pressure. A full restart requeues interrupted RUNNING work.

### Shutdown and startup recovery

The executor participates in Spring's `SmartLifecycle` shutdown, before singleton dependencies / Hikari are destroyed, with `@PreDestroy` as an idempotent fallback. Once shutdown begins it rejects new in-memory dispatch, cancels only waiting `FutureTask`s without changing the durable `QUEUED` rows, and lets active scans finish for `APP_SCAN_SHUTDOWN_GRACE_SECONDS` (default **20**, valid 0–300). After the grace it calls `shutdownNow()`, waits for `APP_SCAN_SHUTDOWN_FORCE_SECONDS` (default **5**, valid 0–60), and suppresses terminal `ERROR` persistence on forced cancellation. A `RUNNING` row is requeued to `QUEUED` at single-instance startup by `ScanJobService.resumeQueued()`; previously queued jobs are dispatched in bounded batches and retried by periodic reconciliation. Scan requests are **at-least-once** across crashes/restarts, not exactly-once: an interrupted scan may run again.

The total shutdown budget must be shorter than `spring.lifecycle.timeout-per-shutdown-phase` (Spring default 30s) and the container's `stop_grace_period` (the supplied Compose uses 60s). Raise both if operators increase the scan-grace period. Scans over a remote socket may not respond to Java thread interruption; worker threads are daemon as the final JVM-exit fallback after both waits. The interrupted job remains `RUNNING` for next boot and must not delete its upload or be marked `ERROR`. If ClamAV never returns, shutting down may still abandon that command on the daemon; account for duplicate daemon execution when running multiple instances (this restart model assumes one application instance per database).

## 2. Measured Java quality non-regression

The Java quality job runs Maven tests for the candidate and its immutable Git base. Full candidate builds enforce the **82% minimum** in `pom.xml`. The relative line-coverage comparison runs when the diff includes `src/`, `pom.xml`, `.mvn/`, `mvnw` or `mvnw.cmd`. It fails when coverage drops by more than **0.01 percentage point**. On a forced full run with no changes to those paths, both Maven test builds, Spotless, SpotBugs/FindSecBugs and PMD still run. The workflow records that the relative comparison is inapplicable. This keeps timing differences in integration tests from failing a CI-only change. Application changes still use measured coverage and can show run-to-run variance.

## 3. Dynamic release upgrade baseline

`issue58-resolve-predecessor.py` selects the most recently published eligible GitHub release with a version earlier than the candidate. This includes earlier releases from a separate release branch. The resolver excludes drafts and the candidate tag and records the selected tag's commit. Both H2 and PostgreSQL jobs use that commit. The upgrade runner checks the tag again before building the predecessor and rejects a moved tag. Expected Flyway versions come from the SQL and Java migrations in each build. Upgrade failures block automated release publication.

`config/upgrade-baseline.json` now documents only the **frozen synthetic fixture provenance**, originally `v2.1beta1`, not a floating previous-release choice. If the latest predecessor's schema cannot accept the frozen fixture, the gate fails closed; maintainers must refresh the fixture after reviewing the schema. It does not silently test a fresh database.

## How to validate

```bash
python3 scripts/check-issue58-hardening.py
python3 scripts/test-issue58-hardening.py
python3 scripts/check-issue57-upgrade-path.py
python3 scripts/test-issue57-upgrade-path.py
mvn -B -ntp clean verify
```

The live upgrade requires a Git checkout with Docker and access to GitHub, Maven repositories and image registries.
