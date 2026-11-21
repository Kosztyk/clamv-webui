# ClamAV Web UI beta2 — Audit fixes #6 and #7 file inventory

**Baseline:** `clamav-webui-v2.1.0beta2-watcher-incremental-fixed-full-source.zip` (retains fixes #1–#5).

## Modified files

- `CHANGELOG.md`
- `README.md`
- `docs/releases/v2.1.0beta2.md`
- `src/main/java/info/trizub/clamav/webclient/service/QuarantineService.java`
- `src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java`
- `src/test/java/info/trizub/clamav/webclient/service/Issue29PartialJobVerdictTest.java`

## New files

- `QUARANTINE_TX_CHANGED_FILES.md`
- `QUARANTINE_TX_VALIDATION.md`
- `src/main/java/info/trizub/clamav/webclient/service/ScanJobFinalizationService.java`
- `src/test/java/info/trizub/clamav/webclient/service/ScanJobFinalizationTransactionTest.java`
- `src/test/java/info/trizub/clamav/webclient/service/SourceQuarantineScopeTest.java`

## Regenerated

- `SOURCE_MANIFEST.sha256` — cryptographic digest inventory for all source files, excluding the manifest itself.

## Behavior

- Source-quarantine scope validation is performed for every ClamD PATH/WATCH detection at the point of move. The current allowed roots and original file/directory scan target (including its original file-vs-directory type) must both authorize that file. Invalid paths are not moved; `VIRUS_FOUND` and detection signatures are retained with `SOURCE_FAILURE`.
- Scan finalization methods delegate through an injected, separately proxied Spring bean with `@Transactional` methods, so internal worker calls actually enter a transaction. Metrics and audit emission occurs after successful transaction commit, rather than before a possible rollback.
- Upload quarantine, ClamD scan transport, bounded watcher, queue lifecycle, public API, and database schema are unchanged.

**No GitHub push, release or deployment was performed.**
