# Directory watcher coverage and retries — issue #17

An enabled watch traverses its complete directory tree on each due poll. The old first-1,000-files and five-level limits have been removed. Eligible files beyond either boundary now create WATCH jobs. Directory symlinks are not followed, preventing recursive cycles; file symlinks are allowed only when their real target is inside an allowed root. Disabled endpoints and current application/daemon scan policies still control submissions.

## Timing and workload

The scheduler checks once a second and respects the live 30–3600-second watch interval. Only one poll runs at a time. A traversal that takes longer than the selected interval completes before another starts; there is no discarded tail or cursor to lose on restart. A large tree can therefore extend the effective interval and add many QUEUED jobs. Worker concurrency remains the configured scan worker count.

Saved size/mtime fingerprints are loaded in one query per watched directory, using an escaped directory prefix with a path separator so sibling directories do not match. Allowed roots are resolved once per poll for traversal checks. Each new/changed job still repeats current path, endpoint and daemon-policy validation at the submission boundary, and execution rechecks policy before scanning.

## Submission and failure behavior

| Situation | Result |
|---|---|
| New or changed eligible file | Commit a durable WATCH job and its new fingerprint together in one per-file transaction |
| Unchanged size/mtime | Retain the fingerprint and avoid duplicate submission |
| Job creation, validation or database write fails before commit | Roll back both changes, retain any old fingerprint, log a WARN with the path and retry on a subsequent poll |
| Marker write fails after job insertion | Roll back the job too; the worker is queued only after a successful commit |
| In-memory enqueue fails after commit | Keep the durable QUEUED job and fingerprint; the existing periodic scan queue reconciler retries enqueueing without creating another job |
| A file/directory is unreadable or resolves outside allowed roots | Log the affected path; continue other readable entries; do not mark it submitted |
| A completed scan has an error verdict | Inspect the Scan Jobs error and correct the cause; a fingerprint records submission, not a clean verdict |

Failures for one file do not stop remaining files from being considered. Each completed directory traversal logs counts for eligible files, submitted jobs, unchanged files, skipped paths and failures. The fingerprint objects loaded for a poll are not mutated before a replacement commits.

The cache uses size and modification time, not a content hash. A rewrite that preserves both values is not a reliable change signal. The existing watcher is a polling scanner rather than a real-time filesystem security monitor.

## Deleted paths and upgrade recovery

An hourly task prunes markers for paths the operating system confirms no longer exist. It also covers rows left by inactive/deleted watch registrations. It reads at most 500 rows at a time, advances by primary key and continues through all batches; deleting one batch cannot shift later rows past an offset. Paths with unknown/inaccessible existence are retained. This removes cache rows only, never source files, scan jobs or audit records.

Rebuild/recreate the Web UI with its existing database, configuration, data and keys. No schema migration is needed. Existing watch registrations and fingerprints remain. The next due poll reaches previously unvisited files beyond the old limits.

If an older deployment may already have recorded a file without a corresponding scan, its historical fingerprint cannot prove scan completion. To request a full resubmission, stop the Web UI and back up its database, then run this statement against that application's database using your normal database administration tool:

```sql
DELETE FROM processed_files;
```

Restart the Web UI with the intended watches enabled. It will resubmit all eligible files in those watches. This clears only the watcher fingerprint cache; existing jobs and audit history remain. An unchanged file with a legitimately queued or completed job normally requires no cache reset.

## Verification

`WatcherPersistenceTest` runs the real JPA repositories, Flyway schema and transactional submission service against H2. It creates 2,500 files and verifies 2,500 durable WATCH jobs/fingerprints and no duplicate jobs on an unchanged second poll. It injects a failure after job insertion for new and changed files, forces a real marker constraint failure, verifies rollback/retry, checks post-commit queue recovery, tests SQL wildcard/prefix boundaries, and prunes more than two batches.

`WatcherCoverageTest` covers deep paths, per-file failure continuation, WARN paths, preservation of previous fingerprints, symlink containment and overlapping polls. Existing scheduling/disabled-endpoint tests remain in `WatcherConfigurationTest`. Run `mvn verify`; see [the delivery validation record](security/watcher-reliability-validation.json) for exact executed checks. Worker enqueueing is mocked in persistence tests, so these tests prove durable job coverage rather than 2,500 live ClamD scans.
