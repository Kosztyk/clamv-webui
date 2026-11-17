# Quarantine identity and aggregate failure recovery (Flyway V11)

**Status:** v2.1.0beta2 local source candidate; NOT a production-tested release.

## Two corrected failure modes

1. **Mixed quarantine outcomes.** A successful permission repair for one infected file must not clear the failure marker for another infected file rejected by path authorization. Before moving any file, the coordinator now reserves a **complete set** of per-detection journal records in one transaction, including a durable `FAILED` record for each rejected or inaccessible candidate. Warning reconciliation inspects every staged detection, not just unfinished journal rows. Missing journal entries are treated as unresolved (fail closed).
2. **Replaced source on restart or before a normal move.** A pathname is not proof of the file that was examined. Each authorized candidate is fingerprinted with streaming SHA-256 and file size **after the ClamAV result and before the journal reservation**. Evidence is committed before any file is moved. The move rechecks that evidence and validates the destination afterward; a content mismatch refuses to move the new occupant or attempts a no-overwrite rollback if it changed during the move. Restart recovery never creates a fresh fingerprint for an unjournaled staged detection; it leaves that path untouched and records a quarantine failure. Recovered PREPARED records without V11 evidence also fail closed instead of moving by pathname alone.

## Schema V11

New nullable columns on `quarantine_artifacts`:

- `source_sha256 VARCHAR(64)` — lowercase SHA-256 content evidence.
- `source_size_bytes BIGINT` — captured file size.
- `failure_code VARCHAR(32)` — explicit failure reason for rejected/invalid detection paths and unavailable evidence.

New nullable `scan_jobs.quarantine_requested BOOLEAN` persists whether quarantine was enabled when the positive scan was staged, so a later settings change cannot silently convert disabled scans into recovery moves, or conceal a missed requested quarantine.

Migrations are supplied for **H2, PostgreSQL, MySQL, and MariaDB**. Existing records remain nullable. They are not backfilled using files at their present location, which could be replacements. The migration contains no mass-file scan or purge.

## Crash-window behavior

| Crash point | Restart action |
|---|---|
| Positive verdict staged but no batch reservation committed | Mark requested quarantine unresolved; **do not move** any file identified only by its pathname |
| Complete batch reserved but no move | Revalidate each source against its original path scope and committed content evidence before moving |
| One of several files moved | Verify existing destination content, repair permissions where necessary, process other reserved files, then finalize |
| A source was replaced, vanished, or became a symlink | Preserve replacement/original location; record quarantine failure and preserve `VIRUS_FOUND` |
| One rejected source and another with pending permission repair | Keep failure warning even after the permission repair succeeds |
| Journal transition or reservation commit fails | Leave positive verdict staged and job unfinished for recovery; do not claim successful quarantine |

A failed post-move rollback is logged and leaves the scan recoverable for further reconciliation; it is not reported as a successful quarantine. Quarantine never overwrites an existing file at the original location on rollback.

## Limits and operating requirements

- Hashing is streaming and memory-bounded but incurs **additional disk I/O proportional to the sizes of detected files**, including a check immediately before movement and a post-move check. Capacity-test with large malware detections on your actual filesystem.
- Source hashes are collected **after** ClamAV returns the detection. If an attacker changes a file between the daemon scanning it and this evidence collection, no pathname-based application can prove that the exact bytes fingerprinted were the bytes originally scanned. For high-assurance workflows use immutable scan snapshots / trusted read-only inputs. The fix specifically closes replacement after journal reservation and during recovery, not every possible concurrent-filesystem race.
- A previously staged scan with missing evidence is deliberately not auto-quarantined on restart. Its `VIRUS_FOUND` verdict is preserved with a `SOURCE_FAILURE` warning and requires operator review or a fresh scan. Do not remove such files based on the old verdict alone.
- Database history retention still honors every existing quarantine artifact. Never delete the quarantine directory or journal rows without a controlled reconciliation procedure.

## Required release verification

1. Back up the database, upload directory and quarantine directory together.
2. Run the complete Maven/JUnit/quality suite, especially `QuarantineEvidenceRegressionTest`, `QuarantineRecoveryCompletenessTest`, `QuarantineArtifactCoordinatorTest` and `QuarantineIdentityMigrationTest`.
3. Apply V1–V11 on fresh databases and V9/V10 → V11 on **all four actual DB vendors**, then validate JPA mappings.
4. Test crashes at staging, batch reservation, individual source move, journal transition and post-commit notification boundaries with multiple infected files. Verify old PREPARED rows without evidence do not move a replacement.
5. Test authorization failures, symlinks, identical-size replacement content, partial directory scan, permission repair with another failed entry, and configuration toggles.
6. Run the live ClamAV and Docker stop/restart suites. Offline source tests alone cannot qualify production deployment.

Do not downgrade an already migrated database to an older binary without restoring a matching backup. V11 schema changes have **not** been exercised against production databases in the packaging environment.

Legacy PREPARED records without saved fingerprints are not treated as successfully verified, even if a file exists at the expected quarantine destination. Recovery records `MISSING_EVIDENCE`, preserves the existing file without moving it, and retains the quarantine-failure warning for manual review.
