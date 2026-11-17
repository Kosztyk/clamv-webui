# Issue #41 — Lossless CSV spreadsheet protection

Issue: https://github.com/Kosztyk/clamv-webui/issues/41

## Root cause

`RestMonitorController.csv` used `replaceAll("^[=+@\\t\\r\\n-]+", "'")` to mitigate spreadsheet formula injection. It **replaced** the entire unsafe prefix instead of **prefixing** the original value. Examples: `-5` was exported as `'5` and `==x` as `'x`. This changes identifiers and negative-number values.

## Fix

Both `/rest-monitor/export?format=csv` and the detailed REST report CSV now use `CsvCellEncoder.encode`. The encoder:

1. Checks for a leading formula/command trigger: `=`, `+`, `-`, `@`, `|`, `%`, as well as leading TAB, CR or LF. It also checks for dangerous triggers after leading whitespace to prevent whitespace-prefixed formula interpretation.
2. Prepends **one** apostrophe to the *unmodified* original value if triggered. It never removes a character from the original input.
3. Applies CSV field quoting and doubles embedded `"` characters. This preserves commas, double quotes, newlines and UTF-8 text as legitimate cell data.

This protects against a wider range of spreadsheet-specific formula syntaxes while retaining the source string after the intentional leading apostrophe. The encoding does **not** rewrite all negative numbers as numeric Excel cells: `-5` becomes the text cell `'-5`, as required by the issue. For raw machine-to-machine data, continue to use `format=json`, which is unchanged.

| Original input | CSV field bytes (shown as text) | Decoded CSV field |
| --- | --- | --- |
| `-5` | `"'-5"` | `'-5` |
| `==x` | `"'==x"` | `'==x` |
| `+identifier` | `"'+identifier"` | `'+identifier` |
| `|cmd` | `"'|cmd"` | `'|cmd` |
| `%value` | `"'%value"` | `'%value` |
| `ordinary-id` | `"ordinary-id"` | `ordinary-id` |
| `a,"b"` | `"a,""b"""` | `a,"b"` |

## Compatibility and configuration

No new configuration flag, database migration, schema change or environment variable is required. The existing API endpoints, column order, authorization and JSON export behavior are unchanged. CSV fields which start with a spreadsheet trigger now intentionally contain a leading apostrophe, including negative numeric strings. Other fields round-trip without modification.

## Verification

From the extracted full source tree:

```bash
python3 scripts/check-issue41-csv-export.py
mvn -B '-Dtest=CsvCellEncoderTest,RestReportServiceTest,RestMonitorIntegrationTest' test
mvn -B verify
```

The static checker compiles the actual encoder with Java 21 and runs 24 behavioral cases plus quote-escaping checks. The Maven tests exercise the encoder, the detailed report exporter and `/rest-monitor/export` end-to-end with an authenticated mock user and mocked upstream API. No external REST scanner or credentials are needed.

As a manual smoke test: request a CSV export including IDs `-5`, `==x` and an ordinary identifier. Confirm `'-5`, `'==x` and the unmodified ordinary identifier. Confirm `format=json` returns the exact original inputs.

Runtime limitation for the prepared offline source package: this environment contains Java 21 but not Maven or Docker, so the full Spring integration test and container build must be run in CI or on the deployment host. Source checks cannot substitute for these tests.
