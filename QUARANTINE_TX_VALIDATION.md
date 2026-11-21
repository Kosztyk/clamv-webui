# ClamAV Web UI beta2 — Quarantine authorization and finalization transaction validation

## Fix #6: Source-file quarantine is target and root scoped

- The untrusted keys in `ScanResult.VirusFound.getFoundViruses()` no longer go straight to `QuarantineService.attempt()` for PATH/WATCH scans.
- `ScanExecutionService` passes the originally authorized scan target plus current `SettingsService.allowedRoots()` to `QuarantineService.attemptSource()`.
- The quarantine service refuses relative, traversal, noncanonical, symlink/alias, off-root, sibling file/directory and stale/unconfigured root paths, checking again immediately before the move. Null root policies fail closed.
- For a single-file scan, only the exact source file is eligible. For a directory scan, only real regular-file descendants of that directory are eligible. `VIRUS_FOUND` verdict and signatures are retained if a move is rejected, together with a `SOURCE_FAILURE` warning.
- Source quarantine decisions use canonical `PathPolicy` checks. File moves cannot be made completely atomic with external filesystem replacements using this Java path-based implementation; a privileged actor capable of racing parent directory replacement remains an environment hardening consideration. Host mounts containing untrusted writers should be protected at the OS/volume permissions level.

## Fix #7: Transaction boundary

- `ScanJobFinalizationService` is a separate `@Service` whose four mutation methods have Spring `@Transactional` annotations. Production `ScanExecutionService` injects this Spring-managed bean and delegates each finalization operation through it; no self-invoked `@Transactional` annotations remain on the executor.
- DB reads and writes happen inside short transactions. Daemon scanning, filesystem quarantine and webhook delivery happen outside these transactions.
- Audit and completion metric updates are deferred with Spring `TransactionSynchronization.afterCommit()`, preventing success events for rolled-back scan writes. Legacy direct-construction unit tests remain supported through the original `ScanExecutionService` overloads.

## Validation actually executed

- Java 21 compiler tree parsing: all 307 Java files (main + tests), zero syntax errors.
- `javac --release 21` compiled the real modified `QuarantineService` and `PathPolicy` against isolated Spring/SLF4J stubs, and a real-filesystem harness passed 9 source quarantine cases (bad paths and valid source moves).
- `javac --release 21` compiled the real new `ScanJobFinalizationService` against isolated stubs.
- Offline scripts passed: `test-full-source-audit-regressions.py`, `test-issue58-hardening.py`, `test-ci-gate.py`, `check-safe-scanning-source.py`, `check-issue36-h2-source.py`, `check-issue53-java-quality.py`, `check-ci-workflow.py`, `check-issue58-hardening.py`, `test-issue53-java-quality.py`, `test-issue55-api-contract.py`, `test-issue52-security-gates.py`.

## New or updated JUnit regression tests (not executed here)

- `SourceQuarantineScopeTest` tests outside allowed root, neighboring paths, traversal and relative spellings, symlinks, null/removed root authorization, target-type replacement, positive single-file and directory quarantines, disabled mode, and a worker path processing a malicious daemon result.
- `ScanJobFinalizationTransactionTest` uses an actual H2 `DataSourceTransactionManager` and Spring AOP proxy to assert the transaction is active on repository access, successful commit emits completion/audit, and a failed save rolls back without emitting a success event.
- `Issue29PartialJobVerdictTest` is updated to pass the required original scan target and root settings.

## Outstanding before production

- Full Maven/JUnit/JaCoCo/Spotless/SpotBugs/PMD and Spring Boot integration tests were **not executed**: Maven and its dependency cache are unavailable in this environment.
- Live ClamD, multiple database backends, Docker shutdown/restart and malicious-daemon behavior require real deployment tests.
- No Flyway migration, Compose file or environment variable changed. Do not deploy until the complete CI pipeline passes.
