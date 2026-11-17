# Quarantine recovery V10 — operator and validation notes

**Status:** local v2.1.0beta2 source candidate. Not yet released or approved for production.

## Why V10 is required

A crash can occur after ClamAV returns `VIRUS_FOUND`, after the verdict is staged,
between individual file moves, or after a move but before the journal transition commits.
To resume safely, the application must know whether the original path target was a
file or a directory. V10 adds nullable `scan_jobs.scan_target_was_directory` to
H2, PostgreSQL, MySQL and MariaDB. Existing rows intentionally retain NULL;
recovery never widens their allowed move scope by guessing from the current
filesystem. A legacy staged positive result can therefore be recorded with a
quarantine-failure warning instead of moving a potentially unauthorized file.

## Crash and failure semantics

For a positive scan, the detection verdict and original scan scope are persisted
before source quarantine begins. Each candidate gets a committed `PREPARED`
journal entry before a file move. Transition failures leave the scan unfinished
for startup reconciliation. Recovery checks the entire persisted detection set,
including candidates for which no journal row was created before termination.
Recovered `HARDEN_FAILED` records are retried, including records attached to
finished jobs. History pruning retains jobs with any existing journaled evidence.

The detection report stored in `scan_jobs.found_viruses_json` is limited to
48 KiB of serialized UTF-8; excess paths or signatures are omitted with a
warning, and the malware verdict remains positive. This cap protects the
MySQL/MariaDB `TEXT` column, but **does not** bound every upstream protocol
parser's in-memory allocations. If the cap is reached, review the warning and
consider a broader incident response/manual scan.

## Deployment checklist

1. Take a verified backup of the database and the configured quarantine and upload directories.
2. Run the complete Maven test and quality suite (`mvn -B -ntp clean verify`) in the supported JDK.
3. Test **both fresh installs and migrations from v9** on H2, PostgreSQL, MySQL and MariaDB.
4. Run Docker/ClamAV crash-injection tests at each journal/FS commit boundary with two or more infected files, including scan cancellation and permission-denied cases.
5. Confirm history retention does not remove rows for any file still quarantined; verify clean, infected and partial-scan API responses.
6. Only after checks pass, deploy in a maintenance window with enough database and Docker stop grace; do not attempt to roll back the binary against a v10 database without restoring a compatible backup.

The included unit tests and standalone Java harness provide partial evidence.
A syntax-only Java parse or source-string check is not a replacement for the
Spring transaction and real-database tests described above.

## V11 follow-up: changed recovery guarantees

V11 supersedes the older assumption above that a staged detection lacking a
journal entry can safely be quarantined after restart. It cannot: the file
may have been replaced. **Only candidates with committed content evidence may
be moved by recovery.** The full detection set is batch-journaled before the
first move, and rejected candidates get `FAILED` rows. See
[quarantine identity and V11 migration](QUARANTINE_IDENTITY_V11.md).
