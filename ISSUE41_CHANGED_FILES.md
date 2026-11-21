# Issue #41 — changed files

- `src/main/java/info/trizub/clamav/webclient/service/CsvCellEncoder.java` — new shared, lossless CSV spreadsheet-protection encoder. Prefixes dangerous input (including `|`/`%` and whitespace-prefixed formulas), then applies normal CSV quoting.
- `src/main/java/info/trizub/clamav/webclient/web/RestMonitorController.java` — replace destructive `replaceAll` sanitizer with shared encoder; all export fields keep original characters.
- `src/main/java/info/trizub/clamav/webclient/service/RestReportService.java` — reuse the same encoder in detailed REST report CSV so the exporters do not drift.
- `src/test/java/info/trizub/clamav/webclient/service/CsvCellEncoderTest.java` — negative values, repeated dangerous prefixes, whitespace, `|`, `%`, normal-value round-trip, and CSV quoting regressions.
- `src/test/java/info/trizub/clamav/webclient/service/RestReportServiceTest.java` — detailed report negative/unsafe fields round-trip without truncation.
- `src/test/java/info/trizub/clamav/webclient/web/RestMonitorIntegrationTest.java` — authenticated HTTP CSV export and unchanged JSON export regression.
- `scripts/check-issue41-csv-export.py` — offline source and isolated Java 21 runtime verification.
- `.github/workflows/issue41-csv-export.yml` — targeted Maven regression CI.
- `docs/ISSUE41_CSV_EXPORT_INTEGRITY.md`, `ISSUE41_VALIDATION_RESULTS.md`, README and documentation updates.
- `SOURCE_MANIFEST.sha256` — refreshed checksums for every file in the complete source archive.

All previous issue #36–#40 source fixes, Configuration Center capabilities, SQL migrations, Docker configurations and other application files are retained.
