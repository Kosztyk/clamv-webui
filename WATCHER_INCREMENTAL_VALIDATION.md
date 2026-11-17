# ClamAV Web UI v2.1.0beta2 — Incremental watcher validation

## Source-level change

- The watcher no longer calls an unbounded `findByPathStartingWith` and no longer builds an entire-tree `HashMap` of historical `ProcessedFile` rows.
- Each one-second scheduled continuation tick is bounded to 256 filesystem entries **across all enabled watched directories** with a cooperative 750 ms work budget.
- Database marker lookups are bounded to 128 exact path values per query, preserving the original unique `path` index.
- Incomplete depth-first cursors continue on the next tick; full watch polling intervals apply after a complete scan cycle. Multiple roots are scheduled fairly.
- On stop, disable, directory removal or application destruction, traversal streams close. An interrupted or database-failed snapshot is eligible for later rediscovery. No DB or runtime setting changes.

## Actually executed validation

- `java -cp /mnt/data JavaSyntaxAudit src` parsed all **304 Java sources** with **0 syntax errors** (Java 21).
- `javac --release 21` successfully compiled the actual modified `WatcherService.java` against small standalone stubs for external Spring/SLF4J/persistence APIs (a compile check of the service, **not** a complete production Maven build).
- A standalone Java filesystem harness using the actual compiled service traversed **901 files**, bounded the first tick to **256**, gave a second watched directory an opportunity, and recorded marker-query batches no larger than **128**.
- `python3 scripts/test-full-source-audit-regressions.py` passed.
- `python3 scripts/check-safe-scanning-source.py` passed.
- `python3 scripts/check-issue36-h2-source.py` passed.
- `python3 scripts/check-issue53-java-quality.py` passed.
- `python3 scripts/check-ci-workflow.py` passed.
- `python3 scripts/check-issue58-hardening.py` passed.
- `python3 scripts/test-ci-gate.py` passed.
- `python3 scripts/test-issue58-hardening.py` passed.
- `python3 scripts/test-issue53-java-quality.py` passed.
- `python3 scripts/test-issue55-api-contract.py` passed.
- `python3 scripts/test-issue52-security-gates.py` passed.
- Complete ZIP CRC, SHA-256 file manifest and real publisher offline `--check`: see package verification below.

## Tests added/updated but not run

- New `WatcherIncrementalTest` includes large-tree multi-tick scanning, batch bounds, round-robin fairness, interval rescan, stop/restart and failed marker lookup retry.
- Updated `WatcherPersistenceTest` tests 2,500 persistent H2 WATCH jobs over repeated ticks, and exact-path marker lookup for paths containing SQL wildcards.
- Updated `WatcherCoverageTest` asserts the indexed marker lookup and preserves security/transaction behavior tests.
- **Maven/JUnit/JaCoCo/Spotless/SpotBugs/PMD, real database integration and live ClamAV are not executed locally:** Maven dependencies and Docker are not available. Run full `mvn -B -ntp clean verify` and live checks on CI before production approval.

## Known design tradeoffs

- The limit is on **filesystem entries examined**, not a hard upper bound on time spent in a slow SQL commit or filesystem syscall.
- Open directory streams are proportional to traversal depth and the number of watched directories with unfinished cycles; they are **not proportional to total historical file count**.
- A changed file arriving after its directory was visited is discovered in a subsequent full cycle; an app restart resumes discovery from the root and skips committed unchanged fingerprints.
- A failed marker lookup is deferred to the next complete traversal rather than falsely marking the file processed. Source submission remains at-least-once and constrained by scan-worker queue capacity.

No GitHub push or release was performed.
