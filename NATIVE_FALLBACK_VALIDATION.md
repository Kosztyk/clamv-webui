# Native fallback source validation — 2026-11-14

## Completed

- Original source archive extracted and compared with the shutdown-lifecycle-fixed baseline; only the declared code/documentation changes and tests were introduced.
- Java 21 parser: **303 Java source files parsed; 0 syntax errors** (JDK tool JavaSyntaxAudit).
- `python3 scripts/check-safe-scanning-source.py`: **PASS**.
- `python3 scripts/check-issue36-h2-source.py`: **PASS** (10 checks).
- `python3 scripts/test-full-source-audit-regressions.py`: **PASS**.
- `python3 scripts/check-ci-workflow.py`: **PASS**.
- SHA-256 manifest regenerated for the full source; ZIP CRC checked and hashes verified.

## Not completed

- **JUnit regression tests have been authored but not run:** Maven and downloaded dependencies are unavailable in this environment.
- Live ClamD native MULTISCAN, fallback, antivirus detection, and Docker shutdown/recovery must be tested before production.
- The broader offline script sweep **timed out** and has not been counted as passing.

## Suggested verification on a machine with JDK 21 + Maven

```bash
mvn -B -ntp -Dtest=ScanExecutionNativeFallbackBoundaryTest,ScanExecutionShutdownLifecycleTest,ScanExecutionQueueCapacityTest test
mvn -B -ntp clean verify
```

## Behavior to verify

1. Native success → exactly one verdict finalization, no INSTREAM retry.
2. Native I/O error with fallback on → exactly one INSTREAM scan and one finalization.
3. Native I/O error with fallback off → one terminal ERROR, no INSTREAM.
4. Persistence, quarantine and notification errors after a native result → **no INSTREAM retry**.
5. Shutdown during an active scan → no false terminal ERROR; recoverable DB state is preserved for restart.
