# Watcher retry ownership and live scan reconciliation (V12 candidate)

## Behavioral contract

- A persisted WATCH fingerprint is associated with its submitted scan job (`last_scan_job_id`), not a successful verdict until that job finishes.
- The marker suppresses duplicate submissions while its scan remains queued/running or has successfully finished.
- A final `ERROR` verdict updates the *currently owned* marker in the same DB transaction with a bounded exponential deadline (30 s initial, maximum 1 h). An old job must never alter a marker taken over by a newer submission.
- After the retry deadline, unchanged files become eligible for a fresh WATCH job. Changing the file fingerprint resets retry history.
- The incremental watcher continues to visit files and roots under its existing 256-entry/tick and 128-marker-lookup limits; no global marker preload was added.
- Live periodic reconciliation recovers bounded pages of stranded staged positive jobs without re-scanning possibly moved or replaced files; it skips jobs owned by local workers. Separately, unowned RUNNING jobs with no staged detection older than five minutes are conditionally returned to QUEUED. Failure attempts are backed off.
- Non-default endpoint directory scans use the same symlink/no-traversal preflight before issuing native MULTISCAN; if local configuration discovery is disabled, a null primary config snapshot does not disable remote scanning. Allowed-root changes still invalidate the scan.
- Public API v1 reports positive detections with truncated persisted results as `INCOMPLETE` with a `DETECTION_REPORT_TRUNCATED` diagnostic. This does not reconstruct omitted detections.

## V12 schema migration

New nullable `processed_files.last_scan_job_id` (varchar 64), non-null `retry_failures` default 0, nullable `retry_after` and an index on `last_scan_job_id` are introduced by Flyway V12 in H2, PostgreSQL, MySQL and MariaDB.

Legacy rows have no known successful job identity. They will be eligible for a one-time rescan when encountered by the incremental watcher. Database backup and rollback planning are mandatory before upgrade.

## Validation / release restriction

Source syntax, source-contract checks and offline Java compilation checks can be run without Maven. They are **not** a substitute for JUnit, real Spring JPA transactional tests, or actual Flyway migrations. Run `mvn -B -ntp clean verify` with JDK 21 and Maven >= 3.9. Then run the fresh/upgrade migrations and Docker/ClamAV restart tests against each supported DB before any production release.

Noteworthy limitations: a multi-instance deployment is not supported by the process-local in-flight ownership mechanism; very large ClamAV reports are still bounded and must be treated as incomplete rather than assumed fully quarantined; file-content evidence is collected after ClamAV scans rather than from an immutable scan snapshot.
