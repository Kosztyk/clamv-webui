# Issue #29 — Directory scanning throughput and partial failures

**Source version:** ClamAV Web UI `v2.1.0beta2` local candidate, incorporating issues #13–#28. No GitHub changes or releases are made by this package.

## What changed

The default endpoint's mounted-daemon-policy directory scanner previously opened one `MULTISCAN <file>` command **sequentially per file** and threw away any partial work after an unreadable file. The revised implementation uses two paths:

1. **Fast directory path:** When the effective daemon configuration has no `ExcludePath` rules, preflight the complete directory without following symlinks. Check canonical allowed-root membership, regular-file types, size limits and local readability. A safe, nonempty tree is sent to the existing bounded ClamAV client in **one native `MULTISCAN <directory>` command**. The preflight is streaming and does not construct a list of all files. A daemon error causes a fallback to the bounded per-file scanner; this can take longer, but reports individual failures instead of discarding detections.
2. **Filtered/recovery path:** If exclusions exist, preflight finds an unsafe/symlinked member, or the directory command fails, traverse safely and send only eligible regular files to ClamAV through a **shared, fixed four-thread executor**. The scan has at most **16 outstanding futures per directory job**, regardless of directory size. This keeps thread count and pending-result memory bounded. Excluded directories are pruned once; each file tests exclusions against its own path, not every ancestor. The initial canonical allowed roots are cached for the scan; each file's current path is still resolved to reject symlink escapes. Changes to daemon policy or allowed roots abort rather than silently widen authorization.

Directory fast-path preflight deliberately disables bulk `MULTISCAN` when a symlink or special file is present. This preserves the previous `walkFileTree` no-follow behavior and does **not** introduce an unreviewed symlink traversal. The allowlisted scan directories must remain administrator-controlled. Changes to directories during a scan are a deployment-level race inherent in daemon path commands; untrusted-writable scan roots should be avoided.

## Explicit partial-result verdict policy

| Results observed | Persisted verdict | Job detail |
| --- | --- | --- |
| All eligible files scanned successfully, no detection | `OK` | Normal clean result |
| Virus detected, no file scan failures | `VIRUS_FOUND` | Detected signatures |
| Virus detected plus one or more unreadable/error files | `VIRUS_FOUND` | Detected signatures **and** bounded partial-scan warning in `errorMessage` |
| One or more failed files, no virus detected | `ERROR` | Bounded partial-scan report with relative filenames and successful-file count; **never falsely `OK`** |
| All files excluded / empty directory | Error (unchanged) | `No eligible regular files remain...` |

Traversal and ClamAV failures are recorded individually and scanning continues when safe. Error details are deliberately restricted to relative file names and sanitized reason categories; there is a maximum of 100 saved per-file details, with the additional failure count shown if needed. An unexpected **policy change** or interruption still terminates the job, as required to preserve access controls and correct cancellation. Existing virus/quarantine behavior for PATH/WATCH jobs is retained. Infected uploaded-file cleanup is unaffected.

## Changed files

- `PathPolicy.java`: reusable per-scan cached canonical allowed-root scope, retaining per-file canonical resolution and the earlier API.
- `DaemonScanPolicy.java`: safe one-command directory path, bounded concurrent filtered path, exclusion pruning, failure aggregation, and partial result report.
- `ScanExecutionService.java`: records partial warnings in job details and prevents partial-clean results from becoming `OK` while keeping detected malware and quarantine behavior.
- `DaemonScanPolicyTest.java`: updated oversized-directory-member expectation to a partial error report.
- `Issue29DirectoryScanTest.java`: new 10,000-file one-command test; exclusions, symlink safety, concurrent worker processing, ClamAV errors, partial results, and fully unreadable trees.
- `Issue29PartialJobVerdictTest.java`: new job-verdict regression tests for partial clean/infected scans.

There are **no SQL migrations**, no Compose/environment-variable changes and no changes to the issue #25 audit, #26 admin validation, #27 cookies, or #28 job-pagination implementations.

## Validation and acceptance tests

Run in a development/staging deployment (JDK 17+ supported by this project's Maven source level):

```bash
mvn -B -Dtest=DaemonScanPolicyTest,Issue29DirectoryScanTest,Issue29PartialJobVerdictTest test
mvn -B verify
```

For a real 10,000-file benchmark, mount a test directory at the same path in the application and `clamd` containers, configure a trusted allowed root and an eligible default endpoint, turn on clamd-config discovery without exclusions, and compare the scan duration to direct `clamd` `MULTISCAN`. Enable SQL/daemon debug logging if necessary. Also introduce an unreadable file and confirm the job detail shows its relative filename while other files are processed. Test a configured `ExcludePath` subtree, a symlink pointing outside the allowed root, and a test virus plus unreadable file.

**Checks performed while building this archive:** Compiled the actual `PathPolicy.java`, `DaemonScanPolicy.java` and `ScanExecutionService.java` with Java 17 language level against minimal service stubs, then ran a standalone actual-code behavior test with 10,000 files and checks for concurrency, exclusions, symlinks and mixed-virus/errors; these passed. Source security scripts and ZIP checks also passed. The Maven test suite, real ClamAV integration, and a true 10,000-file daemon throughput benchmark **were not executed in this environment**. The included 10,000-file JUnit test verifies one daemon command, not a live throughput number.


**Cumulative audit:** The configuration observer now retains the same effective policy object for unchanged file contents and the scan guard uses value-based comparison. This prevents scheduled issue #33 refreshes from aborting normal directory scans; changed policy and allowed roots still fail closed. See [audit remediation](CUMULATIVE_AUDIT_REMEDIATION_13-35.md).
