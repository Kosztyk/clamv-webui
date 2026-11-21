# Directory watcher coverage and retries — issue #17

An enabled watch eventually traverses its complete directory tree in an incremental cycle spread across scheduler ticks. The old first-1,000-files and five-level limits remain removed. Eligible files beyond either boundary create WATCH jobs as their entries are reached. Directory symlinks are not followed, preventing recursive cycles; file symlinks are allowed only when their real target is inside an allowed root. Disabled endpoints and current application/daemon scan policies still control submissions.

## Timing and workload

The scheduler runs every second. A completed scan cycle waits for the configured `app.watch.pollSeconds` interval (5–3600 seconds, default 30) before starting another. **An incomplete cycle continues on the next scheduler tick**, without waiting for the full interval again. Each tick inspects at most **256 filesystem entries across all enabled watched roots** and has an approximately **750 ms cooperative processing budget**. Slow database transactions may make a tick longer than this budget; it is not a hard execution timeout. Large directories are completed over multiple ticks, and other roots receive round-robin opportunities. One poll runs at a time.

Filesystem iteration is lazy and depth-first. The watcher keeps only the open ancestor directory streams for its current unfinished traversals, not a list of every file. Memory for the directory cursors is proportional to **tree depth and active watched roots**, not the number of historical files. Directory streams are closed when the cycle completes, a watch is disabled/removed, the watcher stops, or Spring destroys the service. Restarting the application abandons an unfinished traversal; the next due cycle begins at the root and will skip previously submitted unchanged fingerprints. This is an at-least-once discovery process, not an exactly-once filesystem snapshot.

**Marker lookups use indexed `path IN (...)` equality queries for at most 128 paths**, never a full historical prefix load. Each file's scan-job submission and fingerprint update remain in a single transaction. The existing unique path index is reused; no migration is necessary. A marker-lookup failure skips only the affected batch in that cycle and retries it on a subsequent full cycle. A file may be discovered later when a huge tree is watched, and newly created/deleted files during a traversal may be observed in the next cycle. File and directory symlink restrictions, allowed-root checks, and downstream policy validation are unchanged.

The file-count and depth ceilings remain removed, but individual filesystem operations and database commits can still be slow, especially on network shares. Deep directory nesting consumes one open stream per active ancestor level. Monitor watched root size, available file descriptors, queue pressure and WARN logs for delayed coverage.

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

Failures for one file do not stop remaining files from being considered. Each completed directory traversal logs counts for eligible files, submitted jobs, unchanged files, skipped paths and failures. Fingerprints returned for a small lookup batch are not mutated before a replacement commits.

The cache uses size and modification time, not a content hash. A rewrite that preserves both values is not a reliable change signal. The existing watcher is a polling scanner rather than a real-time filesystem security monitor.

## Deleted paths and upgrade recovery

An hourly task prunes markers for paths the operating system confirms no longer exist. It also covers rows left by inactive/deleted watch registrations. It reads at most 500 rows at a time, advances by primary key and continues through all batches; deleting one batch cannot shift later rows past an offset. Paths with unknown/inaccessible existence are retained. This removes cache rows only, never source files, scan jobs or audit records.

Rebuild/recreate the Web UI with its existing database, configuration, data and keys. No schema migration is needed. Existing watch registrations and fingerprints remain. The next due traversal cycle progressively reaches previously unvisited files beyond the old limits.

If an older deployment may already have recorded a file without a corresponding scan, its historical fingerprint cannot prove scan completion. To request a full resubmission, stop the Web UI and back up its database, then run this statement against that application's database using your normal database administration tool:

```sql
DELETE FROM processed_files;
```

Restart the Web UI with the intended watches enabled. It will resubmit all eligible files in those watches. This clears only the watcher fingerprint cache; existing jobs and audit history remain. An unchanged file with a legitimately queued or completed job normally requires no cache reset.

## Verification

`WatcherPersistenceTest` runs the real JPA repositories, Flyway schema and transactional submission service against H2. It creates 2,500 files and advances multiple scheduled ticks to verify 2,500 durable WATCH jobs/fingerprints and no duplicate jobs on an unchanged rescan. It injects a failure after job insertion for new and changed files, forces a real marker constraint failure, verifies rollback/retry, checks post-commit queue recovery, tests indexed exact-path lookup for wildcard-containing and sibling names, and prunes more than two batches.

`WatcherCoverageTest` covers deep paths, per-file failure continuation, WARN paths, preservation of previous fingerprints, symlink containment and overlapping polls. `WatcherIncrementalTest` covers the per-tick entry cap, 128-key lookup cap, round-robin root fairness, timing, interruption of an in-progress cycle, and retry after a transient marker lookup error. Existing scheduling/disabled-endpoint tests remain in `WatcherConfigurationTest`. Run `mvn verify`; see [the delivery validation record](security/watcher-reliability-validation.json) for exact executed checks. Worker enqueueing is mocked in persistence tests, so these tests prove durable job coverage rather than 2,500 live ClamD scans.
