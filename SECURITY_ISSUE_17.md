# Security issue #17: watcher coverage and failed-submission recovery

The watcher now traverses the full tree without the 1,000-file or five-level cap. A per-file transaction commits each WATCH job and fingerprint together. Failed creation/marker writes roll back and remain eligible for retry; errors identify their paths at WARN. Existing committed QUEUED work remains recoverable by the queue reconciler.

Fingerprints load once per watched directory, allowed traversal roots are resolved once per poll, and hourly keyset-batch cleanup removes cache rows for deleted paths. Polls cannot overlap. No database schema change is required.

This complete source retains issues #13–#16. See [behavior and legacy cache recovery](docs/WATCHER_RELIABILITY.md), [executed validation](docs/security/watcher-reliability-validation.json) and [changed files](CHANGED_FILES.md).
