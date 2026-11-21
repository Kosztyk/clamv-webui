# ClamAV Web UI v2.1.0beta2 — Quarantine identity and mixed-warning correction validation

## Scope

Fixes two reproduced defects without discarding earlier cumulative audit fixes:

1. An independent rejected-path quarantine failure could be lost when a different artifact's filesystem permissions were repaired. A batch journal now records **every attempted detection**, including rejected or unavailable paths, and the warning reconciler examines the **complete staged detection set**. Journal-state updates are committed before finalizing a positive scan.
2. Recovery could quarantine a replacement file at a previously infected pathname. Source artifacts now store a SHA-256 digest and byte size **before the first move**. The normal source path and restart recovery verify identity before moving; destinations are also verified. Missing/legacy evidence fails closed without moving the source, preserving the warning. The upload path also uses persisted content evidence.

## Executed validations

- Standalone JDK 21 behavioral harness compiled the actual `QuarantineArtifactCoordinator`, `QuarantineService`, `QuarantineFileIdentity`, `QuarantineArtifact`, and `ScanJob` sources using minimal test doubles; **25 assertions passed**, including mixed failure + permission recovery, replacement with same size and timestamp, crash after one of two infected files moved, missing journal, tampered destination, disabled quarantine, and legacy missing-evidence cases.
- **38/38** source, security, API, schema-inventory, CI, release, and cumulative-offline repository checks passed; **0 failed, 0 timed out**.
- JDK 21 parser successfully parsed **320 Java source files**, no syntax errors (not a full Java compile).
- Complete source archive CRC, unique member names, regenerated per-file SHA-256 source manifest, source privacy audit, and actual offline release publisher preflight passed.

## Still required before production

- `mvn -B -ntp clean verify` to execute JUnit tests, integration tests, Checkstyle/JaCoCo and Spring dependency-aware compilation.
- Flyway V11 fresh installation **and** V10→V11 upgrade tests on **H2, PostgreSQL, MySQL and MariaDB**.
- Live ClamAV/Docker interruption tests: staged positive detection, replacement during and after scanning, journal commit failure, restart with mixed `FAILED`/`HARDEN_FAILED` artifacts, concurrent file replacement, symlinks, and scan shutdown.
- Review performance cost of SHA-256 hashing on large source files. The SHA-256 snapshot is taken **after** ClamAV reports an infected path; it does not cryptographically attest the exact content previously scanned if an attacker can change it before the snapshot. Stronger guarantees require quarantining a fixed snapshot/open file handle and scanning that same immutable copy.

**Status:** Source-level and standalone functional checks pass; application-wide runtime correctness and production suitability are **not established**.
