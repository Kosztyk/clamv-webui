# Issue #18: upload completion cleanup and retention

Clean uploads are deleted by default after completion. Infected copies are quarantined or deleted. Multi-file request failures and confirmed transaction rollbacks remove their full/partial filesystem copies, while committed queued work remains recoverable.

Hourly configurable upload retention protects active jobs and copies in progress, expires managed orphan/completed files, and offers independent opt-in job/audit history pruning. Uploads use owner-only POSIX permissions. Bundled native scans keep working through a UID-aligned image derived from the same pinned official ClamAV base; its read-only upload-only mount and private-state isolation remain.

This full source retains issues #13–#17. See [configuration and scanner migration](docs/UPLOAD_RETENTION.md), [validation](docs/security/upload-retention-validation.json) and [changed files](CHANGED_FILES.md). No schema migration is required; retain existing database/configuration and their keys.
