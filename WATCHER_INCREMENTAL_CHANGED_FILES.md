# Watcher incremental polling — changed files

- `src/main/java/info/trizub/clamav/webclient/service/WatcherService.java` — incremental, time/entry-bounded traversal, lazy directory cursors, batch lookup and safe cursor cleanup.
- `src/main/java/info/trizub/clamav/webclient/repo/ProcessedFileRepository.java` — replace unbounded prefix fetch with indexed `findByPathIn(Collection<String>)`.
- `src/test/java/info/trizub/clamav/webclient/service/WatcherCoverageTest.java` — adapt regression expectations to indexed batch lookups.
- `src/test/java/info/trizub/clamav/webclient/service/WatcherPersistenceTest.java` — assert multi-tick completion for 2,500 files and exact path lookup.
- `src/test/java/info/trizub/clamav/webclient/service/WatcherIncrementalTest.java` — new incremental traversal, bounds, fairness, rescan, stop/restart and database lookup retry tests.
- `README.md`, `CHANGELOG.md`, `docs/WATCHER_RELIABILITY.md`, `docs/COMPLETE_USER_GUIDE.md`, `docs/releases/v2.1.0beta2.md` — document behavior and limits.
- `WATCHER_INCREMENTAL_VALIDATION.md` — validation evidence and remaining tests.
- `SOURCE_MANIFEST.sha256` — regenerated manifest.

No Flyway migrations, Docker Compose, Java workers, or ClamAV transport changes. All previous cumulative fixes remain present.
