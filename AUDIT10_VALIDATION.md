# ClamAV Web UI beta2 — ten-finding cumulative patch validation

Baseline: `clamav-webui-v2.1.0beta2-audit-fix8-cleanup-full-source.zip`

## Implemented

1. Mixed partial-scan/quarantine warnings are parsed per line; v1 API returns a quarantine failure when one occurs.
2. Processed-file markers now include endpoint IDs. All four Flyway V9 migrations replace path-only uniqueness with endpoint+path/digest uniqueness. Legacy null-owner rows are ignored and lazily pruned.
3. A per-file `quarantine_artifacts` journal records source, destination, signatures, status and timestamps. The first successful source destination also appears in the existing scan-job field without exposing paths in the public API.
4. Two stale mandatory CI checks now validate the current source quarantine and post-commit audit boundaries.
5. Watcher cursors have independent next-eligible deadlines so completed small roots can restart while large roots are unfinished.
6. Pre-move journal reservation, deterministic destination, pre-move positive staging, startup reconciliation and idempotent finalization recover interrupted quarantine without rescanning moved files. Recovery repairs permissions when possible and fails closed when database reconciliation cannot complete.
7. Upload quarantine runtime exceptions preserve confirmed infected verdicts; staged positive upload recoveries avoid downgrading on missing upload originals.
8. Combined shutdown waits are capped at 40s, checked against the configured Spring phase timeout (with 5s margin). Default Spring phase timeout is 45s and packaged Docker stop grace 60s.
9. Filtered directory result aggregation bounds reported paths to 1,024 and signatures per path to 16; overflow marks a partial scan rather than silently truncating successful results.
10. The worker's `QUEUED -> RUNNING` claim and stop transition share the same dispatch lock.

## Validation performed locally

- Full Java 21 AST parsing: 312 Java files, no parser errors. This detects syntax, not type-checking or correct Spring/JPA wiring.
- Both previously failing mandatory CI source checks now pass.
- Source-level H2, API, CI, security, migration inventory, quality, watcher and issue #44 checks pass. The broader offline sweep timed out after 25 consecutive passes; no claim is made about all remaining checks.
- Java 21 standalone compilation of the actual modified QuarantineService plus actual PathPolicy and PrivateFiles: 3 filesystem scenarios passed (authorized deterministic move, rejected sibling target, invalid journal ID).
- Tests added/updated for independent watcher rescan timing, endpoint reassignment, overlapping endpoint-backed persisted markers, mixed quarantine errors, quarantine journal reservation/move/recovery and positive verdict preservation. Those JUnit tests have NOT been run because Maven and external dependencies are unavailable.

## Required CI / production evidence STILL MISSING

- A complete `mvn -B -ntp clean verify` and all new JUnit tests, including full Spring bean wiring and startup ordering.
- Flyway upgrades and JPA schema validation on H2, PostgreSQL, MySQL, MariaDB, plus database-specific integration testing. Migration SQL is source-reviewed, not executed against the four engines.
- Docker stop/restart tests (idle, active scan, forced cancellation, database loss after quarantine move).
- Live ClamAV scan with malware detections, partial tree failures, duplicate endpoints and a very large watched tree.
- Browser/API integration and release pipeline; new package was NOT published.

## Deployment / rollback warning

**Flyway V9 is a new schema migration.** Back up H2/PostgreSQL/MySQL/MariaDB and persistent volumes before updating. Legacy watcher fingerprints are deliberately ignored and eventually deleted, so expect a one-time full watched-tree rescan. Restore backups rather than running an old application against migrated V9 data.

**Release assessment: candidate only; NOT production-approved until the missing tests pass.** Source review cannot guarantee that no additional problems exist.
