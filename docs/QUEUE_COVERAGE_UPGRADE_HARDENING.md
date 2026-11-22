# Post-audit fixes: scan dispatch, Java quality, release upgrades

## 1. Bounded, durable scan dispatch

The scan worker now has a bounded `LinkedBlockingQueue` of **256 IDs by default**, configurable with `APP_SCAN_QUEUE_CAPACITY` / `app.scan.queue-capacity` (valid range 1–10000; restart required). The separate `app.concurrentScans` property still controls worker concurrency. Because accepted jobs are **committed to `scan_jobs` before local dispatch**, a full queue does not lose a job or cause an after-commit HTTP 500. Instead, it removes the rejected ID from the in-memory deduplication set, increments `clamav.scan.dispatch.deferred`, and leaves the record `QUEUED`. The existing scheduled `ScanJobService.reconcileQueued()` retries bounded DB batches (default 60 seconds) and workers atomically claim QUEUED → RUNNING. A one-worker/one-slot JUnit concurrency test exercises this behavior and startup capacity validation.

**Residual risk:** This limits only process memory, not the number of rows stored in the DB. Apply upload/API rate limits, disk quotas and retention. When scans are slower than intake, waiting time grows; the metric indicates pressure. A full restart requeues interrupted RUNNING work.

### Shutdown and startup recovery

The executor participates in Spring's `SmartLifecycle` shutdown, before singleton dependencies / Hikari are destroyed, with `@PreDestroy` as an idempotent fallback. Once shutdown begins it rejects new in-memory dispatch, cancels only waiting `FutureTask`s without changing the durable `QUEUED` rows, and lets active scans finish for `APP_SCAN_SHUTDOWN_GRACE_SECONDS` (default **20**, valid 0–300). After the grace it calls `shutdownNow()`, waits for `APP_SCAN_SHUTDOWN_FORCE_SECONDS` (default **5**, valid 0–60), and suppresses terminal `ERROR` persistence on forced cancellation. A `RUNNING` row is requeued to `QUEUED` at single-instance startup by `ScanJobService.resumeQueued()`; previously queued jobs are dispatched in bounded batches and retried by periodic reconciliation. Scan requests are **at-least-once** across crashes/restarts, not exactly-once: an interrupted scan may run again.

The total shutdown budget must be shorter than `spring.lifecycle.timeout-per-shutdown-phase` (Spring default 30s) and the container's `stop_grace_period` (the supplied Compose uses 60s). Raise both if operators increase the scan-grace period. Scans over a remote socket may not respond to Java thread interruption; worker threads are daemon as the final JVM-exit fallback after both waits. The interrupted job remains `RUNNING` for next boot and must not delete its upload or be marked `ERROR`. If ClamAV never returns, shutting down may still abandon that command on the daemon; account for duplicate daemon execution when running multiple instances (this restart model assumes one application instance per database).

## 2. Measured Java quality non-regression

The Java quality CI executes Maven tests for both the candidate and its immutable Git base and uses matching JaCoCo instrumentation (injected into older temporary baseline POMs if needed). The **relative** line-coverage ratchet in `scripts/issue58-coverage-ratchet.py` is applied only when coverage-affecting Java/application paths changed (`pom.xml`, Maven wrapper/configuration, Java sources/tests/resources, or application resources). Forced full-pipeline runs caused only by CI/docs changes still execute both Maven/JaCoCo builds, SpotBugs/FindSecBugs and PMD, but do not compare nondeterministic integration-test coverage between identical application trees. For applicable source changes, the ratchet permits at most **0.01 percentage point** of variance; any larger decrease remains blocking. The Maven 5% absolute minimum is always enforced. SpotBugs/FindSecBugs and PMD continue blocking additional findings relative to the base.

**Historical debt remains:** There is no trustworthy coverage measurement available in this environment to justify increasing the 5% absolute floor; it would be incorrect to claim the entire historical Java codebase has been reformatted. The `scripts/issue58-java-modernize.sh` script performs an opt-in whole-project `spotless:apply` with the pinned formatter in a **clean Git checkout** with JDK 21 / Maven 3.9+, exporting a reviewable patch. Commit that as a separate formatting-only PR, run the full Maven suite, then replace incremental `ratchetFrom` enforcement with whole-tree formatting. Raise the JaCoCo floor only once the actual report has been examined.

## 3. Dynamic release upgrade baseline

`issue58-resolve-predecessor.py` queries **published GitHub releases** and selects the newest published tag whose peeled commit is a strict ancestor of the candidate's immutable Git commit, excluding drafts, the candidate tag and unrelated branches. Both H2/PostgreSQL CI and release jobs run it with a read-only GitHub token and full Git history. It writes `artifacts/upgrade-predecessor.json`, and the upgrade runner re-resolves the tag before building the predecessor, refusing a moved tag. Both pre- and post-upgrade Flyway versions are computed dynamically, including Java and SQL migrations. The release remains blocked if a real previous database fails to migrate or retain its users, tokens, jobs or encrypted settings.

`config/upgrade-baseline.json` now documents only the **frozen synthetic fixture provenance**, originally `v2.1beta1`, not a floating previous-release choice. If the latest predecessor's schema cannot accept the frozen fixture, the gate fails closed; maintainers must refresh the fixture after reviewing the schema. It does not silently test a fresh database.

## How to validate

```bash
python3 scripts/check-issue58-hardening.py
python3 scripts/test-issue58-hardening.py
python3 scripts/check-issue57-upgrade-path.py
python3 scripts/test-issue57-upgrade-path.py
mvn -B -ntp clean verify
```

The live upgrade requires a GitHub Actions checkout with Docker and network access to GitHub, Maven repositories and image registries. Full formatter execution and Maven/JUnit/JaCoCo tests were not possible in the offline source-review environment. These conditions must pass in CI before production adoption.
