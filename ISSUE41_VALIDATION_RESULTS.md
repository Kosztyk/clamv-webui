# Issue #41 — validation results (2026-11-13)

**Baseline:** `clamav-webui-v2.1.0beta2-issue40-rest-security-full-source.zip`.
**Issue:** https://github.com/Kosztyk/clamv-webui/issues/41

## Executed here

- **PASS** — issue #41 static regression checks: **13/13**.
- **PASS** — Java 21 compiled and executed the actual `CsvCellEncoder` implementation against 24 input cases (14 malicious/risky, 10 ordinary) plus an exact CSV quote-escaping assertion.
- **PASS** — issue #36 H2 source checks: 10/10.
- **PASS** — issue #37 Docker maintenance source checks: 15/15.
- **PASS** — issue #38 ClamdTop security source checks: 22/22.
- **PASS** — issue #38 Configuration Center source checks: 17/17.
- **PASS** — issue #39 viewer/webhook source checks: 35/35.
- **PASS** — issue #40 REST transport source checks: 31/31.
- **PASS** — all changed Java classes integrate with the same CSV codec; no extra configuration or schema change.

Total source check assertions: **143/143**, including issue #41's 13 checks. Prior checks ensure existing fixes are still structurally present, but do not replace runtime regression tests.

## Added JUnit/Spring tests (not run here)

- `CsvCellEncoderTest` — negative numbers, `==x`, `|`, `%`, tabs/CR/LF, leading whitespace, quotes, commas, Unicode and safe-value round-trip.
- `RestReportServiceTest` — dangerous filenames, signatures, IDs and request IDs in the detailed CSV.
- `RestMonitorIntegrationTest` — mocked upstream data through `/rest-monitor/export?format=csv`, plus JSON export unchanged.

The full Maven and Docker integration tests **were not executed** in this environment (Maven and Docker are unavailable); consequently, the HTTP controller test is present but not yet validated by a running Spring test context.

## Next release gate

```bash
python3 scripts/check-issue41-csv-export.py
mvn -B '-Dtest=CsvCellEncoderTest,RestReportServiceTest,RestMonitorIntegrationTest' test
mvn -B verify
docker build --pull -t clamav-web-client:issue41 .
```

After building, exercise the CSV export and inspect the results in both a text editor and the spreadsheet program you support. Confirm that dangerous values have a **leading apostrophe** while the rest of the original text is preserved. For completely unmodified machine-readable values, use `format=json`.

No production release, Docker Hub publication, GitHub push or GitHub issue transition was performed as part of preparing this offline archive.
