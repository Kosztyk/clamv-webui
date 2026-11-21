# v2.1.0beta2 post-audit — scan-worker lifecycle fix

Based on the uploaded `clamav-webui-v2.1.0beta2-post-audit-queue-quality-upgrade-hardening-full-source(1).zip`. Only the shutdown lifecycle finding (audit item 1) was addressed; the separate remaining audit findings are not silently changed. Version remains `2.1.0-beta2`. No Flyway migration or new dependencies.

## Production changes

- `src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java` — implement `SmartLifecycle` and idempotent `@PreDestroy` shutdown; synchronize dispatch with shutdown; cancel unstarted local tasks, preserve DB `QUEUED` state; allow active scans to finish in grace, interrupt after timeout; avoid writing terminal `ERROR` on cancellation; daemon fallback for clamd I/O that cannot be interrupted.
- `src/main/java/info/trizub/clamav/webclient/service/ScanJobService.java` — skip the scheduled queued-job DB reconciliation once scan dispatch has stopped, preserving the already-existing bulk `RUNNING` → `QUEUED` startup recovery.
- `src/main/resources/application.properties` — introduce `APP_SCAN_SHUTDOWN_GRACE_SECONDS=20` and `APP_SCAN_SHUTDOWN_FORCE_SECONDS=5` defaults.

## Tests and enforcement

- `src/test/java/info/trizub/clamav/webclient/service/ScanExecutionShutdownLifecycleTest.java` — new graceful-shutdown, queued-work/restart, interruption/restart concurrency tests.
- `src/test/java/info/trizub/clamav/webclient/service/Issue28StartupRecoveryTest.java` — keep the mocked worker marked ready and assert reconciliation performs no DB query after stop.
- `src/test/java/info/trizub/clamav/webclient/service/WatcherPersistenceTest.java` — make the mocked lifecycle state explicit in the post-commit queue-retry integration test.
- `scripts/check-issue58-hardening.py` — static CI guard against deleting the worker lifecycle and cancellation handling.

## Documentation / packaging

- `README.md`, `CHANGELOG.md`, `docs/QUEUE_COVERAGE_UPGRADE_HARDENING.md`, `docs/releases/v2.1.0beta2.md` — default budgets, restart behavior, operational caveats.
- `SCAN_SHUTDOWN_FIX_CHANGED_FILES.md`, `SCAN_SHUTDOWN_FIX_VALIDATION_RESULTS.md` — file inventory and validation status.
- `SOURCE_MANIFEST.sha256` — regenerated for all contents after modification.

Deployment: keep the supplied `stop_grace_period: 60s` or set a longer value than the configured shutdown budgets. Ensure `spring.lifecycle.timeout-per-shutdown-phase` also exceeds their sum. `RUNNING` jobs are retried from the beginning after an interruption; do not run two Web UI instances against the same DB using this single-instance startup recovery design.
