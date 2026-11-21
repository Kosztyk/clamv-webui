# Native upload scan / INSTREAM fallback scope — v2.1.0beta2

Baseline: `clamav-webui-v2.1.0beta2-scan-shutdown-lifecycle-fixed-full-source.zip`. The scan-worker shutdown lifecycle fix is retained.

## Files changed (existing)

- `src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java` — native path scanner now **returns** a `ScanResult`; only the I/O command is within the `catch (IOException)` fallback boundary; `handleResult()` executes once outside the catch for either transport. Database persistence, quarantine and notifications can no longer accidentally trigger INSTREAM fallback. Unexpected runtime exceptions do not trigger a second scan.
- `CHANGELOG.md` — records the correction.
- `docs/releases/v2.1.0beta2.md` — explains updated transport/failure semantics and test requirements.

## Files added

- `src/test/java/info/trizub/clamav/webclient/service/ScanExecutionNativeFallbackBoundaryTest.java` — seven deterministic Mockito/JUnit tests of native success, I/O fallback enabled/disabled, DB persistence failure, quarantine failure, notification failure and unexpected native runtime failures.
- `NATIVE_FALLBACK_CHANGED_FILES.md` and `NATIVE_FALLBACK_VALIDATION.md` — local source-candidate documentation.

## Unchanged

- Scan-worker `SmartLifecycle` shutdown and its concurrency/restart tests
- H2 shutdown settings, Flyway migrations, database schema, API, UI, Docker config
- Native upload and INSTREAM scanning feature toggles

No database migration or environment-variable changes are required. This archive is **not** a published image or release.
